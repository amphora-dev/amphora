package com.winlator.cmod.runtime.display.environment.components;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.system.OsConstants;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Exposes Android's active network addresses to Wine. Wine cannot enumerate
 * Android interfaces directly, so NetworkInfoUpdateComponent writes these
 * values into the prefix for iphlpapi/ws2_32.
 */
public class NetworkHelper {
    private final ConnectivityManager connectivityManager;

    public static class IFAddress {
        public String name = "eth0";
        public int flags = 0;
        public int family = OsConstants.AF_INET;
        public int scopeId = 0;
        public String address = "0";
        public String netmask = "0";

        @Override
        public String toString() {
            return name + "," + flags + "," + family + "," + scopeId + "," + address + "," + netmask;
        }
    }

    public NetworkHelper(Context context) {
        this.connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public List<IFAddress> getIFAddresses() {
        ArrayList<IFAddress> result = new ArrayList<>();
        Network activeNetwork = connectivityManager.getActiveNetwork();
        LinkProperties linkProperties =
                activeNetwork != null ? connectivityManager.getLinkProperties(activeNetwork) : null;
        if (linkProperties == null) return result;
        for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
            InetAddress address = linkAddress.getAddress();
            if ((address instanceof Inet4Address) || (address instanceof Inet6Address)) {
                IFAddress ifAddress = new IFAddress();
                if (address instanceof Inet6Address) {
                    ifAddress.family = OsConstants.AF_INET6;
                    ifAddress.scopeId = ((Inet6Address) address).getScopeId();
                }
                ifAddress.address = address.getHostAddress();
                ifAddress.netmask =
                        formatNetmask(
                                linkAddress.getPrefixLength(), address instanceof Inet6Address);
                ifAddress.flags = OsConstants.IFF_UP | OsConstants.IFF_RUNNING;
                result.add(ifAddress);
            }
        }
        return result;
    }

    public static String formatIpAddress(int ipAddress) {
        return (ipAddress & 255) + "." + ((ipAddress >> 8) & 255) + "."
                + ((ipAddress >> 16) & 255) + "." + ((ipAddress >> 24) & 255);
    }

    public static String formatNetmask(int prefixLength, boolean ipv6) {
        int addressBits = ipv6 ? 128 : 32;
        if (prefixLength < 0 || prefixLength > addressBits) return "";

        if (!ipv6) {
            long mask = prefixLength == 0 ? 0 : (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
            return ((mask >>> 24) & 0xff)
                    + "."
                    + ((mask >>> 16) & 0xff)
                    + "."
                    + ((mask >>> 8) & 0xff)
                    + "."
                    + (mask & 0xff);
        }

        StringBuilder mask = new StringBuilder();
        int remainingBits = prefixLength;
        for (int group = 0; group < 8; group++) {
            int groupBits = Math.min(16, remainingBits);
            int value = groupBits == 0 ? 0 : (0xffff << (16 - groupBits)) & 0xffff;
            if (group > 0) mask.append(':');
            mask.append(Integer.toHexString(value));
            remainingBits -= groupBits;
        }
        return mask.toString();
    }
}
