# https://github.com/xjasonlyu/tun2socks/wiki/Examples
# https://github.com/eycorsican/leaf/blob/master/leaf/src/sys.rs
# https://github.com/eycorsican/leaf/blob/master/leaf/src/common/cmd_macos.rs
# https://github.com/mellow-io/mellow/blob/f71f6e54768ded3cfcc46bebb706d46cb8baac08/src/main.js#L702
# https://github.com/mellow-io/mellow/blob/f71f6e54768ded3cfcc46bebb706d46cb8baac08/src/helper/win32/config_route.bat

# In this example, "WIFI" is the default primary network interface.
function tun_create() {
    sudo ./tun2socks-darwin-amd64-v3 -device wintun -proxy socks5://127.0.0.1:2081 -interface "WIFI"
}

# Use ifconfig to bring the TUN interface up and assign addresses for it.
function tun_set_address() {
   netsh interface ipv4 set address name="wintun" source=static addr=198.18.0.1 mask=255.255.255.0
}


# Add TUN inteface route.
function tun_setup_route() {
   # Mode 1:
   # Add these specific routes so that tun2socks can handle primary connections.

   # Mode 2:
   # netsh interface ipv4 add route 0.0.0.0/0 "wintun" 198.18.0.1 metric=1
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
