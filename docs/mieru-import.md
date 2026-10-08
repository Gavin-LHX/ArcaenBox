# Mieru simple-link import

ArcaenBox 1.7.1 adds `mierus://` import through the shared clipboard, subscription, QR-code and Android link parser. The port is read from the `port` query parameter. Username and password use URI percent encoding.

The node retains `profile`, `protocol`, `mtu`, `multiplexing` and `handshake-mode`. Multiplexing and handshake mode are editable and survive database storage, backup and sharing. The external core receives these options while still connecting through sing-box's protected loopback mapping.

Existing version-zero Mieru profiles remain readable. Their previously absent multiplexing and handshake fields default to LOW and STANDARD. TCP profiles now retain their MTU during serialization, too.

This importer supports one server port and protocol per node. Multiple bindings, port ranges and `traffic-pattern` are rejected rather than silently discarded. The protobuf-based `mieru://` format is different and is not an alias for `mierus://`.

References for the pinned Mieru 3.36.1 core:

- [Official simple-link implementation](https://github.com/enfein/mieru/blob/v3.36.1/pkg/appctl/url.go)
- [Core profile validation](https://github.com/enfein/mieru/blob/v3.36.1/pkg/appctl/appctlcommon/client.go)

Regression fixtures use synthetic credentials and documentation-only addresses. They cover query-port import, shared parser dispatch, IPv6 and percent encoding, all stored options, legacy TCP/UDP records, generated core configuration and invalid input. No user-provided credentials belong in tests or release artifacts.
