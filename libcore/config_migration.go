package libcore

import (
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
)

type configObject = map[string]any

func object(v any) configObject { m, _ := v.(map[string]any); return m }
func array(v any) []any {
	if v == nil {
		return nil
	}
	if a, ok := v.([]any); ok {
		return a
	}
	return []any{v}
}
func str(v any) string                    { s, _ := v.(string); return s }
func take(m configObject, key string) any { v := m[key]; delete(m, key); return v }

// Translate legacy ArcaenBox profiles in memory. Stored user profiles are never rewritten.
// The same normalization is used by connection tests, VPN startup and custom JSON profiles.
func migrateConfig(input string) ([]byte, error) {
	var root configObject
	if err := json.Unmarshal([]byte(input), &root); err != nil {
		return nil, err
	}
	if root == nil {
		return nil, fmt.Errorf("configuration must be an object")
	}
	route := object(root["route"])
	if route == nil {
		route = configObject{}
		root["route"] = route
	}
	dns := object(root["dns"])
	dnsStrategies := map[string]string{}
	rcodeServers := map[string]string{}
	var fakeTags []string
	if dns != nil {
		fake := object(take(dns, "fakeip"))
		delete(dns, "independent_cache") // Always independent in 1.14.
		var servers []any
		for _, raw := range array(dns["servers"]) {
			s := object(raw)
			if s == nil {
				return nil, fmt.Errorf("invalid DNS server")
			}
			if str(s["type"]) != "" && s["type"] != "legacy" {
				servers = append(servers, s)
				continue
			}
			address := str(take(s, "address"))
			tag := str(s["tag"])
			if address == "" {
				return nil, fmt.Errorf("DNS server %q has no address", tag)
			}
			dnsStrategies[tag] = str(take(s, "strategy"))
			resolver := str(take(s, "address_resolver"))
			strategy := take(s, "address_strategy")
			fallback := take(s, "address_fallback_delay")
			if resolver != "" {
				s["domain_resolver"] = configObject{"server": resolver}
				if str(strategy) != "" {
					object(s["domain_resolver"])["strategy"] = strategy
				}
			}
			if fallback != nil {
				s["fallback_delay"] = fallback
			}
			switch {
			case strings.HasPrefix(address, "rcode://"):
				code := strings.ToUpper(strings.TrimPrefix(address, "rcode://"))
				if code == "SUCCESS" {
					code = "NOERROR"
				}
				if code == "NAME_ERROR" {
					code = "NXDOMAIN"
				}
				rcodeServers[tag] = code
				continue
			case address == "local":
				s["type"] = "local"
				delete(s, "detour")
			case address == "fakeip":
				s = configObject{"tag": tag, "type": "fakeip"}
				for _, key := range []string{"inet4_range", "inet6_range"} {
					if fake[key] != nil {
						s[key] = fake[key]
					}
				}
				fakeTags = append(fakeTags, tag)
			default:
				a := address
				if !strings.Contains(a, "://") {
					a = "udp://" + a
				}
				u, err := url.Parse(a)
				if err != nil {
					return nil, fmt.Errorf("invalid DNS server %q", tag)
				}
				typ := u.Scheme
				if typ == "h3" {
					typ = "http3"
				}
				switch typ {
				case "udp", "tcp", "tls", "https", "quic", "http3":
				default:
					return nil, fmt.Errorf("unsupported legacy DNS protocol %q", typ)
				}
				s["type"] = typ
				host := u.Hostname()
				if host == "" {
					return nil, fmt.Errorf("empty DNS host")
				}
				s["server"] = host
				if p := u.Port(); p != "" {
					port, e := strconv.Atoi(p)
					if e != nil || port < 1 || port > 65535 {
						return nil, fmt.Errorf("invalid DNS port")
					}
					s["server_port"] = port
				}
				if typ == "https" || typ == "http3" {
					if u.Path != "" {
						s["path"] = u.EscapedPath()
						if u.RawQuery != "" {
							s["path"] = str(s["path"]) + "?" + u.RawQuery
						}
					}
				}
				if typ == "tls" || typ == "https" || typ == "quic" || typ == "http3" {
					s["tls"] = configObject{"enabled": true}
				}
				// Legacy remote DNS uses the default outbound; modern DNS defaults to direct.
				if str(s["detour"]) == "" {
					if final := str(route["final"]); final != "" {
						s["detour"] = final
					} else if len(array(root["outbounds"])) > 0 {
						s["detour"] = object(array(root["outbounds"])[0])["tag"]
					}
				}
				if s["detour"] == "direct" {
					delete(s, "detour")
				}
			}
			servers = append(servers, s)
		}
		dns["servers"] = servers
		var rules []any
		for _, raw := range array(dns["rules"]) {
			rule := object(raw)
			if rule == nil {
				return nil, fmt.Errorf("invalid DNS rule")
			}
			if rule["outbound"] != nil {
				matches := array(take(rule, "outbound"))
				if len(matches) == 1 && matches[0] == "any" && len(rule) <= 2 {
					if route["default_domain_resolver"] == nil {
						route["default_domain_resolver"] = rule["server"]
					}
					continue
				}
				return nil, fmt.Errorf("legacy outbound DNS rule requires a domain_resolver on the outbound")
			}
			migrateDNSRule(rule, dnsStrategies, rcodeServers, fakeTags)
			rules = append(rules, rule)
		}
		final := str(dns["final"])
		if code, ok := rcodeServers[final]; ok {
			rules = append(rules, configObject{"action": "predefined", "rcode": code})
			delete(dns, "final")
		}
		if strategy := dnsStrategies[final]; strategy != "" {
			dns["strategy"] = strategy
		}
		if len(rules) > 0 {
			dns["rules"] = rules
		} else {
			delete(dns, "rules")
		}
	}
	if route["default_domain_resolver"] == nil && dns != nil {
		for _, s := range array(dns["servers"]) {
			if object(s)["tag"] == "dns-direct" {
				route["default_domain_resolver"] = "dns-direct"
				break
			}
		}
	}
	var before []any
	for index, raw := range array(root["inbounds"]) {
		in := object(raw)
		if in == nil {
			return nil, fmt.Errorf("invalid inbound")
		}
		tag := str(in["tag"])
		if tag == "" {
			tag = fmt.Sprintf("arcaenbox-inbound-%d", index)
			in["tag"] = tag
		}
		sniff := take(in, "sniff")
		override := take(in, "sniff_override_destination")
		timeout := take(in, "sniff_timeout")
		if sniff == true {
			rule := configObject{"inbound": []any{tag}, "action": "sniff"}
			if override == true {
				rule["override_destination"] = true
			}
			if timeout != nil {
				rule["timeout"] = timeout
			}
			before = append(before, rule)
		}
		strategy := str(take(in, "domain_strategy"))
		if strategy != "" && strategy != "as_is" {
			before = append(before, configObject{"inbound": []any{tag}, "action": "resolve", "strategy": strategy})
		}
		delete(in, "udp_disable_domain_unmapping")
		if in["type"] == "tun" {
			for _, part := range []string{"address", "route_address", "route_exclude_address"} {
				a4, a6 := take(in, "inet4_"+part), take(in, "inet6_"+part)
				if a4 != nil || a6 != nil {
					in[part] = append(append(array(in[part]), array(a4)...), array(a6)...)
				}
			}
			delete(in, "endpoint_independent_nat")
			delete(in, "gso")
		}
	}
	special := map[string]string{}
	var outbounds []any
	for _, raw := range array(root["outbounds"]) {
		out := object(raw)
		if out == nil {
			return nil, fmt.Errorf("invalid outbound")
		}
		if out["type"] == "dns" || out["type"] == "block" {
			action := "reject"
			if out["type"] == "dns" {
				action = "hijack-dns"
			}
			special[str(out["tag"])] = action
			continue
		}
		if out["type"] == "wireguard" {
			endpoint := migrateWireguard(out)
			root["endpoints"] = append(array(root["endpoints"]), endpoint)
			continue
		}
		if strategy := str(take(out, "domain_strategy")); strategy != "" && strategy != "as_is" && out["domain_resolver"] == nil {
			resolver := str(route["default_domain_resolver"])
			if resolver == "" && dns != nil {
				resolver = str(dns["final"])
			}
			if resolver != "" {
				out["domain_resolver"] = configObject{"server": resolver, "strategy": strategy}
			}
		}
		outbounds = append(outbounds, out)
	}
	if root["outbounds"] != nil {
		root["outbounds"] = outbounds
	}
	rules := array(route["rules"])
	for _, r := range rules {
		migrateRouteRule(object(r), special)
	}
	if action := special[str(route["final"])]; action != "" {
		rules = append(rules, configObject{"action": action})
		delete(route, "final")
	}
	if len(before) > 0 || len(rules) > 0 {
		route["rules"] = append(before, rules...)
	}
	return json.Marshal(root)
}

func migrateDNSRule(rule configObject, strategies map[string]string, rcodes map[string]string, fakeTags []string) {
	for _, child := range array(rule["rules"]) {
		migrateDNSRule(object(child), strategies, rcodes, fakeTags)
	}
	server := str(rule["server"])
	if code, ok := rcodes[server]; ok {
		delete(rule, "server")
		delete(rule, "disable_cache")
		rule["action"] = "predefined"
		rule["rcode"] = code
		return
	}
	if strategy := strategies[server]; strategy != "" && rule["strategy"] == nil {
		rule["strategy"] = strategy
	}
	for _, tag := range fakeTags {
		if server == tag && rule["query_type"] == nil {
			rule["query_type"] = []any{"A", "AAAA"}
			delete(rule, "strategy")
		}
	}
}
func migrateRouteRule(rule configObject, special map[string]string) {
	for _, child := range array(rule["rules"]) {
		migrateRouteRule(object(child), special)
	}
	if action := special[str(rule["outbound"])]; action != "" {
		delete(rule, "outbound")
		rule["action"] = action
	}
}
func migrateWireguard(out configObject) configObject {
	e := configObject{"type": "wireguard", "tag": out["tag"], "address": out["local_address"], "private_key": out["private_key"]}
	for _, k := range []string{"mtu", "workers", "detour", "domain_resolver", "bind_interface", "routing_mark", "connect_timeout"} {
		if out[k] != nil {
			e[k] = out[k]
		}
	}
	peer := configObject{"address": out["server"], "port": out["server_port"], "public_key": out["peer_public_key"], "allowed_ips": []any{"0.0.0.0/0", "::/0"}}
	for _, k := range []string{"pre_shared_key", "reserved"} {
		if out[k] != nil {
			peer[k] = out[k]
		}
	}
	e["peers"] = []any{peer}
	if peers := array(out["peers"]); len(peers) > 0 {
		e["peers"] = peers
		for _, p := range peers {
			m := object(p)
			if endpoint := str(take(m, "server")); endpoint != "" {
				host, port, err := net.SplitHostPort(endpoint)
				if err == nil {
					m["address"] = host
					m["port"], _ = strconv.Atoi(port)
				}
			}
			if m["allowed_ips"] == nil {
				m["allowed_ips"] = []any{"0.0.0.0/0", "::/0"}
			}
		}
	}
	return e
}
