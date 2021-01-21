package org.azi.socks.proxy;

//要请求的地址类型
public enum ADDRESS_TYPE {
    IPV4((byte) 0X01, "the address is a version-4 IP address, with a length of 4 octets"),
    DOMAIN((byte) 0X03, "the address field contains a fully-qualified domain name.  The first\n" +
            "   octet of the address field contains the number of octets of name that\n" +
            "   follow, there is no terminating NUL octet."),
    IPV6((byte) 0X04, "the address is a version-6 IP address, with a length of 16 octets.");
    byte value;
    String description;

    ADDRESS_TYPE(byte value, String description) {
        this.value = value;
        this.description = description;
    }

    public static ADDRESS_TYPE convertToAddressType(byte value) {
        for (ADDRESS_TYPE addressType : ADDRESS_TYPE.values()) {
            if (addressType.value == value) {
                return addressType;
            }
        }
        return null;
    }

}