//! Explicit trust boundary for the unauthenticated video TCP listener.

use std::net::{IpAddr, SocketAddr, TcpListener};

pub fn resolve_bind_address(
    port: u16,
    cli_address: Option<IpAddr>,
    cli_allow_lan: bool,
    env_address: Option<&str>,
    env_allow_lan: Option<&str>,
) -> Result<SocketAddr, String> {
    let address = match cli_address {
        Some(address) => address,
        None => env_address
            .unwrap_or("127.0.0.1")
            .parse::<IpAddr>()
            .map_err(|_| "WBEAM_STREAM_BIND_ADDR must be an IP address".to_string())?,
    };
    let allow_lan = cli_allow_lan || env_allow_lan == Some("1");
    if !address.is_loopback() && !allow_lan {
        return Err(
            "Refusing to expose the unauthenticated video stream beyond localhost. \
             For a trusted interface only, set WBEAM_STREAM_BIND_ADDR and \
             WBEAM_ALLOW_UNAUTHENTICATED_LAN_STREAM=1, or pass --stream-bind-address \
             and --allow-unauthenticated-lan-stream."
                .to_string(),
        );
    }
    Ok(SocketAddr::new(address, port))
}

pub fn bind_listener(address: SocketAddr) -> std::io::Result<TcpListener> {
    let listener = TcpListener::bind(address)?;
    listener.set_nonblocking(true)?;
    Ok(listener)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_binds_a_real_ipv4_loopback_socket() {
        let address = resolve_bind_address(0, None, false, None, None).unwrap();
        let listener = bind_listener(address).unwrap();
        assert_eq!(listener.local_addr().unwrap().ip(), "127.0.0.1".parse::<IpAddr>().unwrap());
    }

    #[test]
    fn loopback_ipv6_is_allowed_but_remote_addresses_need_opt_in() {
        assert!(resolve_bind_address(5000, None, false, Some("::1"), None).is_ok());
        for address in ["0.0.0.0", "192.168.42.1", "10.0.0.2", "::", "2001:db8::1"] {
            assert!(resolve_bind_address(5000, None, false, Some(address), None).is_err());
            assert!(resolve_bind_address(5000, None, false, Some(address), Some("0")).is_err());
            assert!(resolve_bind_address(5000, None, false, Some(address), Some("1")).is_ok());
        }
    }

    #[test]
    fn opt_in_alone_does_not_expand_exposure() {
        let address = resolve_bind_address(5000, None, true, None, Some("1")).unwrap();
        assert!(address.ip().is_loopback());
    }

    #[test]
    fn invalid_addresses_fail_closed_and_cli_takes_precedence() {
        for address in ["", "localhost", "127.0.0.1:5000", "not-an-ip"] {
            assert!(resolve_bind_address(5000, None, true, Some(address), Some("1")).is_err());
        }
        let local = "127.0.0.1".parse().unwrap();
        assert_eq!(
            resolve_bind_address(5000, Some(local), false, Some("0.0.0.0"), None).unwrap(),
            SocketAddr::new(local, 5000)
        );
    }

    #[test]
    fn a_busy_port_is_reported_before_a_sender_thread_starts() {
        let address = resolve_bind_address(0, None, false, None, None).unwrap();
        let listener = bind_listener(address).unwrap();
        assert!(bind_listener(listener.local_addr().unwrap()).is_err());
    }
}
