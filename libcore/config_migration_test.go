package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/option"
)

const oldConfig = `{
 "log":{"disabled":true},
 "inbounds":[{"type":"tun","tag":"tun-in","inet4_address":["172.19.0.1/28"],"endpoint_independent_nat":true,"sniff":true,"sniff_override_destination":true,"domain_strategy":"prefer_ipv4"}],
 "outbounds":[{"type":"selector","tag":"proxy","outbounds":["direct"]},{"type":"direct","tag":"direct"},{"type":"block","tag":"block"},{"type":"dns","tag":"dns-out"}],
 "dns":{"independent_cache":true,"final":"dns-remote","fakeip":{"enabled":true,"inet4_range":"198.18.0.0/15","inet6_range":"fc00::/18"},"servers":[
  {"address":"rcode://success","tag":"dns-block"},
  {"address":"local","tag":"dns-local","detour":"direct"},
  {"address":"223.5.5.5","tag":"dns-direct","detour":"direct","address_resolver":"dns-local","strategy":"prefer_ipv4"},
  {"address":"https://dns.example/dns-query","tag":"dns-remote","address_resolver":"dns-direct","strategy":"prefer_ipv4"},
  {"address":"fakeip","tag":"dns-fake","strategy":"ipv4_only"}],
  "rules":[{"outbound":["any"],"server":"dns-direct"},{"domain":["blocked.example"],"server":"dns-block"},{"inbound":["tun-in"],"server":"dns-fake","disable_cache":true}]},
 "route":{"rules":[{"port":[53],"outbound":"dns-out"},{"domain":["blocked.example"],"outbound":"block"}]}
}`

func parseMigrated(t *testing.T, input string) (context.Context, option.Options, configObject) {
	t.Helper()
	content, err := migrateConfig(input)
	if err != nil {
		t.Fatal(err)
	}
	ctx := box.Context(context.Background(), arcaenboxAndroidInboundRegistry(), arcaenboxAndroidOutboundRegistry(), arcaenboxAndroidEndpointRegistry(), arcaenboxAndroidDNSTransportRegistry(nil), arcaenboxAndroidServiceRegistry(), certificate.NewRegistry())
	var options option.Options
	if err = options.UnmarshalJSONContext(ctx, content); err != nil {
		t.Fatalf("%v\n%s", err, content)
	}
	var m configObject
	_ = json.Unmarshal(content, &m)
	return ctx, options, m
}

func TestLegacyDNSAndTunMigration(t *testing.T) {
	_, _, m := parseMigrated(t, oldConfig)
	route := object(m["route"])
	dns := object(m["dns"])
	if route["default_domain_resolver"] != "dns-direct" {
		t.Fatal("lost node DNS resolver")
	}
	if dns["fakeip"] != nil || dns["independent_cache"] != nil {
		t.Fatal("legacy DNS options remain")
	}
	servers := array(dns["servers"])
	if object(servers[2])["detour"] != "proxy" {
		t.Fatal("remote DNS bypassed proxy")
	}
	rules := array(dns["rules"])
	if object(rules[0])["rcode"] != "NOERROR" {
		t.Fatal("blocking DNS response changed")
	}
	if len(array(object(rules[1])["query_type"])) != 2 {
		t.Fatal("fake DNS must only handle A/AAAA")
	}
	in := object(array(m["inbounds"])[0])
	if len(array(in["address"])) != 1 || in["sniff"] != nil {
		t.Fatal("legacy TUN remains")
	}
	if object(array(route["rules"])[0])["override_destination"] != true {
		t.Fatal("lost sniff override")
	}
	again, err := migrateConfig(string(mustJSON(m)))
	if err != nil {
		t.Fatal(err)
	}
	var same configObject
	_ = json.Unmarshal(again, &same)
	if string(mustJSON(same)) != string(mustJSON(m)) {
		t.Fatal("migration is not idempotent")
	}
}
func mustJSON(v any) []byte {
	b, e := json.Marshal(v)
	if e != nil {
		panic(e)
	}
	return b
}
func mustURL(t *testing.T, value string) *url.URL {
	t.Helper()
	u, e := url.Parse(value)
	if e != nil {
		t.Fatal(e)
	}
	return u
}

func TestWireguardMigration(t *testing.T) {
	_, _, m := parseMigrated(t, `{"outbounds":[{"type":"wireguard","tag":"proxy","server":"127.0.0.1","server_port":51820,"local_address":["10.0.0.2/32"],"private_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","peer_public_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","reserved":"AQID","mtu":1408}],"route":{"final":"proxy"}}`)
	if len(array(m["outbounds"])) != 0 || len(array(m["endpoints"])) != 1 {
		t.Fatal("wireguard was not migrated to endpoint")
	}
	e := object(array(m["endpoints"])[0])
	if object(array(e["peers"])[0])["reserved"] != "AQID" {
		t.Fatal("lost reserved bytes")
	}
}

func TestMigratedCoreProxyTraffic(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "arcaenbox-core-traffic-ok") }))
	defer server.Close()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	var raw configObject
	_ = json.Unmarshal([]byte(oldConfig), &raw)
	raw["inbounds"] = []any{configObject{"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": port, "sniff": true}}
	ctx, options, _ := parseMigrated(t, string(mustJSON(raw)))
	b, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	stats := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{Enabled: true, Outbounds: []string{"proxy"}})
	b.Router().AppendTracker(stats.StatsService())
	// Exercise a real HTTP CONNECT/mixed inbound, routing and outbound path.
	proxyURL := "http://127.0.0.1:" + fmt.Sprint(port)
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = http.ProxyURL(mustURL(t, proxyURL))
	client := &http.Client{Transport: transport, Timeout: 5 * time.Second}
	defer transport.CloseIdleConnections()
	response, err := client.Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil || string(body) != "arcaenbox-core-traffic-ok" {
		t.Fatalf("unexpected response %q %v", body, err)
	}
	time.Sleep(50 * time.Millisecond)
	if stats.QueryStats("outbound>>>proxy>>>traffic>>>downlink") <= 0 {
		t.Fatal("traffic stats were not recorded")
	}
}

func TestRejectInvalidLegacyDNS(t *testing.T) {
	for _, input := range []string{`null`, `{"dns":{"servers":[{"address":"unsupported://bad"}]}}`, `{"dns":{"rules":[{"outbound":["specific-proxy"],"server":"x"}]}}`} {
		if _, err := migrateConfig(input); err == nil {
			t.Fatal("invalid migration accepted", input)
		}
	}
}

func TestPreserveModernConfiguration(t *testing.T) {
	_, _, m := parseMigrated(t, `{"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"servers":[{"type":"https","tag":"doh","server":"1.1.1.1"}]},"route":{"final":"direct"}}`)
	if strings.Contains(string(mustJSON(m)), "domain_resolver") {
		t.Fatal("modern resolver was changed")
	}
}
