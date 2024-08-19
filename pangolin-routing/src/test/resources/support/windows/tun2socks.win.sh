# https://github.com/xjasonlyu/tun2socks/wiki/Examples
# https://github.com/eycorsican/leaf/blob/master/leaf/src/sys.rs
# https://github.com/eycorsican/leaf/blob/master/leaf/src/common/cmd_macos.rs
# https://github.com/mellow-io/mellow/blob/f71f6e54768ded3cfcc46bebb706d46cb8baac08/src/main.js#L702
# https://github.com/mellow-io/mellow/blob/f71f6e54768ded3cfcc46bebb706d46cb8baac08/src/helper/win32/config_route.bat

# In Windows, we need to start tun2socks first so that it will create TUN interface for us.
# In this example, "以太网 2" is the default primary network interface.
function tun_create() {
    tun2socks.exe -device wintun -proxy socks5://127.0.0.1:3081 -interface "以太网 2"
}

# Use netsh to bring the TUN interface up and assign addresses for it.
function tun_set_address() {
    # netsh interface ipv4 set address name="wintun" source=static address=198.18.0.1 mask=255.255.255.0 gateway=198.18.0.1
    netsh interface ipv4 set address name="wintun" source=static address=198.18.0.1 mask=255.255.255.0
}


# Add TUN inteface route.
function tun_setup_route() {
   # Mode 1: Add these specific routes so that tun2socks can handle primary connections.

   # Mode 2: Route default traffic to TUN interface.
   # netsh interface ipv4 add route 0.0.0.0/0 "wintun" 198.18.0.1 metric=1
   # or
   # Remove default route
   # route delete 0.0.0.0 mask 0.0.0.0
   # Add default route
   # route add 0.0.0.0 mask 0.0.0.0 198.18.0.1 metric 6
}


# Cleanup TUN interface route.
function tun_cleanup_route() {
}

function tun_init() {
   tun_set_address
   tun_setup_route
}

# In Windows, we usually need to manually set up the DNS address for our interface.
# netsh interface ipv4 set dnsservers name="wintun" static address=8.8.8.8 register=none validate=no
netsh interface ipv4 set dnsservers name="wintun" static address=127.0.0.1 register=none validate=no
# ipconfig /flushdns
