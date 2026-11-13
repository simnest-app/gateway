# libplum in CloudSim

- Upstream: https://github.com/paullouisageneau/libplum, tag v0.6.0 (archive sha256 `f8b2290f94b5b38ab26f5b07cb8204eb3af218adbf434a9f0a227e90d80a320c`).
- Licence: MPL-2.0 (`LICENSE`). Kept: `src/`, `include/`, `LICENSE`, the upstream README. Left out: the example, build files, the logo.
- Used by the gateway's audio bridge to ask the home router for a UDP port (PCP, NAT-PMP or UPnP IGD), see
  `docs/architecture/DIRECT_PATHS.md`. Built into `libplumjni.so` with `../../plumjni.c`.
- Changed: `src/net.c`, default-gateway lookup over Netlink prefers the route out of the Wi-Fi interface (Android keeps a
  default route per network, cellular included). Marked "CloudSim change". The changed file stays under MPL-2.0.
