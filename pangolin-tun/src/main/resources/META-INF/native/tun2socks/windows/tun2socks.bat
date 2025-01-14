@rem # https://github.com/xjasonlyu/tun2socks/wiki/Examples
@rem # https://github.com/eycorsican/leaf/blob/master/leaf/src/sys.rs
@rem # https://github.com/eycorsican/leaf/blob/master/leaf/src/common/cmd_macos.rs
@rem # https://github.com/mellow-io/mellow/blob/f71f6e54768ded3cfcc46bebb706d46cb8baac08/src/main.js#L702
@rem # https://github.com/mellow-io/mellow/blob/f71f6e54768ded3cfcc46bebb706d46cb8baac08/src/helper/win32/config_route.bat
@rem
@rem # In Windows, we need to start tun2socks first so that it will create TUN interface for us.
@rem # In this example, "ÒÔÌ«Íø 2" is the default primary network interface.
@rem function tun_create() {
@rem     tun2socks.exe -device wintun -proxy socks5://127.0.0.1:3081 -interface "ÒÔÌ«Íø 2"
@rem }
@rem
@rem
@rem function tun_start() {
@rem    # Use netsh to bring the TUN interface up and assign addresses for it.
@rem    # netsh interface ipv4 set address name="wintun" source=static address=198.18.0.1 mask=255.255.255.0 gateway=198.18.0.1
@rem    netsh interface ipv4 set address name="wintun" source=static address=198.18.0.1 mask=255.255.255.0
@rem
@rem    # Add TUN inteface route.
@rem    # Mode 1: Add these specific routes so that tun2socks can handle primary connections.
@rem
@rem    # Mode 2: Route default traffic to TUN interface.
@rem    # netsh interface ipv4 add route 0.0.0.0/0 "wintun" 198.18.0.1 metric=1
@rem    # or
@rem    # Remove default route
@rem    # route delete 0.0.0.0 mask 0.0.0.0
@rem    # Add default route
@rem    # route add 0.0.0.0 mask 0.0.0.0 198.18.0.1 metric 6
@rem
@rem    # In Windows, we usually need to manually set up the DNS address for our interface.
@rem    # netsh interface ipv4 set dnsservers name="wintun" static address=8.8.8.8 register=none validate=no
@rem    netsh interface ipv4 set dnsservers name="wintun" static address=127.0.0.1 register=none validate=no
@rem
@rem    ipconfig /flushdns
@rem }
@rem
@rem
@rem function tun_stop() {
@rem     # Cleanup TUN interface route.
@rem }


amd64\tun2socks.exe -device wintun -proxy socks5://127.0.0.1:3081 -interface "ÒÔÌ«Íø 2" --tun-post-up "cmd /C netsh interface ipv4 set address name=wintun source=static address=198.18.0.1 mask=255.255.255.0 && netsh interface ipv4 set dnsservers name=wintun static address=127.0.0.1 register=none validate=no && netsh interface ipv4 set dnsservers name=wintun static address=127.0.0.1 register=none validate=no && ipconfig /flushdns"
