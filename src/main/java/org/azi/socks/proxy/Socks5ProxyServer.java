package org.azi.socks.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
 
/**
 * socks5代理服务器简单实现
 *
 * <a>https://www.ietf.org/rfc/rfc1928.txt</a>
 * <p>
 * <p>
 * 使用socks5代理的坑，域名在本地解析还是在代理服务器端解析，有些比如google.com就必须在代理服务器端解析
 * <a>https://blog.emacsos.com/use-socks5-proxy-in-curl.html</a>
 *
 */
public class Socks5ProxyServer {
 
    // 服务监听在哪个端口上
    private static Integer SERVICE_LISTENER_PORT = 10086;
 
    // 能够允许的最大客户端数量
    private static Integer MAX_CLIENT_NUM = 100;
 
    // 用于统计客户端的数量
    private static AtomicInteger clientNumCount = new AtomicInteger();
 
    // socks协议的版本，固定为5
    private static final byte VERSION = 0X05;
    // RSV，必须为0
    private static final byte RSV = 0X00;
 
    private static String SERVER_IP_ADDRESS;
 
    static {
        try {
            SERVER_IP_ADDRESS = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }
 
    public static class ClientHandler implements Runnable {
 
        private Socket clientSocket;
        private String clientIp;
        private int clientPort;
 
        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
            this.clientIp = clientSocket.getInetAddress().getHostAddress();
            this.clientPort = clientSocket.getPort();
        }
 
        @Override
        public void run() {
            try {
 
                // 协商认证方法
                negotiationCertificationMethod();
 
                // 开始处理客户端的命令
                handleClientCommand();
 
            } catch (Exception e) {
                handleLog("exception, " + e.getMessage());
            } finally {
                close(clientSocket);
                handleLog("client dead, current client count=%s", clientNumCount.decrementAndGet());
            }
        }
 
        // 协商与客户端的认证方法
        private void negotiationCertificationMethod() throws IOException {
            InputStream is = clientSocket.getInputStream();
            OutputStream os = clientSocket.getOutputStream();
            byte[] buff = new byte[255];
            // 接收客户端的支持的方法
            is.read(buff, 0, 2);
            int version = buff[0];
            int methodNum = buff[1];
 
            if (version != VERSION) {
                throw new RuntimeException("version must 0X05");
            } else if (methodNum < 1) {
                throw new RuntimeException("method num must gt 0");
            }
 
            is.read(buff, 0, methodNum);
            List<METHOD> clientSupportMethodList = METHOD.convertToMethod(Arrays.copyOfRange(buff, 0, methodNum));
            handleLog("version=%s, methodNum=%s, clientSupportMethodList=%s", version, methodNum, clientSupportMethodList);
 
            // 向客户端发送回应，这里不进行认证
            buff[0] = VERSION;
            buff[1] = METHOD.NO_AUTHENTICATION_REQUIRED.getRangeStart();
            os.write(buff, 0, 2);
            os.flush();
        }
 
        // 认证通过，开始处理客户端发送过来的指令
        private void handleClientCommand() throws IOException {
            InputStream is = clientSocket.getInputStream();
            OutputStream os = clientSocket.getOutputStream();
            byte[] buff = new byte[255];
            // 接收客户端命令
            is.read(buff, 0, 4);
            int version = buff[0];
            COMMAND command = COMMAND.convertToCmd(buff[1]);
            int rsv = buff[2];
            ADDRESS_TYPE addressType = ADDRESS_TYPE.convertToAddressType(buff[3]);
            if (rsv != RSV) {
                throw new RuntimeException("RSV must 0X05");
            } else if (version != VERSION) {
                throw new RuntimeException("VERSION must 0X05");
            } else if (command == null) {
                // 不支持的命令
                sendCommandResponse(COMMAND_STATUS.COMMAND_NOT_SUPPORTED);
                handleLog("not supported command");
                return;
            } else if (addressType == null) {
                // 不支持的地址类型
                sendCommandResponse(COMMAND_STATUS.ADDRESS_TYPE_NOT_SUPPORTED);
                handleLog("address type not supported");
                return;
            }
 
            String targetAddress = "";
            switch (addressType) {
                case DOMAIN:
                    // 如果是域名的话第一个字节表示域名的长度为n，紧接着n个字节表示域名
                    is.read(buff, 0, 1);
                    int domainLength = buff[0];
                    is.read(buff, 0, domainLength);
                    targetAddress = new String(Arrays.copyOfRange(buff, 0, domainLength));
                    break;
                case IPV4:
                    // 如果是ipv4的话使用固定的4个字节表示地址
                    is.read(buff, 0, 4);
                    targetAddress = ipAddressBytesToString(buff);
                    break;
                case IPV6:
                    throw new RuntimeException("not support ipv6.");
            }
 
            is.read(buff, 0, 2);
            int targetPort = ((buff[0] & 0XFF) << 8) | (buff[1] & 0XFF);
 
            StringBuilder msg = new StringBuilder();
            msg.append("version=").append(version).append(", cmd=").append(command.name())
                    .append(", addressType=").append(addressType.name())
                    .append(", domain=").append(targetAddress).append(", port=").append(targetPort);
            handleLog(msg.toString());
 
            // 响应客户端发送的命令，暂时只实现CONNECT命令
            switch (command) {
                case CONNECT:
                    handleConnectCommand(targetAddress, targetPort);
                case BIND:
                    throw new RuntimeException("not support command BIND");
                case UDP_ASSOCIATE:
                    throw new RuntimeException("not support command UDP_ASSOCIATE");
            }
 
        }
 
        // convert ip address from 4 byte to string
        private String ipAddressBytesToString(byte[] ipAddressBytes) {
            // first convert to int avoid negative
            return (ipAddressBytes[0] & 0XFF) + "." + (ipAddressBytes[1] & 0XFF) + "." + (ipAddressBytes[2] & 0XFF) + "." + (ipAddressBytes[3] & 0XFF);
        }
 
        // 处理CONNECT命令
        private void handleConnectCommand(String targetAddress, int targetPort) throws IOException {
            Socket targetSocket = null;
            try {
                targetSocket = new Socket(targetAddress, targetPort);
            } catch (IOException e) {
                sendCommandResponse(COMMAND_STATUS.GENERAL_SOCKS_SERVER_FAILURE);
                return;
            }
            sendCommandResponse(COMMAND_STATUS.SUCCEEDED);
            new SocketForwarding(clientSocket, targetSocket).start();
        }
 
        private void sendCommandResponse(COMMAND_STATUS commandStatus) throws IOException {
            OutputStream os = clientSocket.getOutputStream();
            os.write(buildCommandResponse(commandStatus.getRangeStart()));
            os.flush();
        }
 
        private byte[] buildCommandResponse(byte commandStatusCode) {
            ByteBuffer payload = ByteBuffer.allocate(100);
            payload.put(VERSION);
            payload.put(commandStatusCode);
            payload.put(RSV);
//          payload.put(ADDRESS_TYPE.IPV4.value);
//          payload.put(SERVER_IP_ADDRESS.getBytes());
            payload.put(ADDRESS_TYPE.DOMAIN.value);
            byte[] addressBytes = SERVER_IP_ADDRESS.getBytes();
            payload.put((byte) addressBytes.length);
            payload.put(addressBytes);
            payload.put((byte) (((SERVICE_LISTENER_PORT & 0XFF00) >> 8)));
            payload.put((byte) (SERVICE_LISTENER_PORT & 0XFF));
            byte[] payloadBytes = new byte[payload.position()];
            payload.flip();
            payload.get(payloadBytes);
            return payloadBytes;
        }
 
        private void handleLog(String format, Object... args) {
            log("handle, clientIp=" + clientIp + ", port=" + clientPort + ", " + format, args);
        }
 
    }
 
    // 用来连接客户端和目标服务器转发流量
    public static class SocketForwarding {
 
        // 客户端socket
        private Socket clientSocket;
        private String clientIp;
        // 目标地址socket
        private Socket targetSocket;
        private String targetAddress;
        private int targetPort;
 
        public SocketForwarding(Socket clientSocket, Socket targetSocket) {
            this.clientSocket = clientSocket;
            this.clientIp = clientSocket.getInetAddress().getHostAddress();
            this.targetSocket = targetSocket;
            this.targetAddress = targetSocket.getInetAddress().getHostAddress();
            this.targetPort = targetSocket.getPort();
        }
 
        public void start() {
            OutputStream clientOs = null;
            InputStream clientIs = null;
            InputStream targetIs = null;
            OutputStream targetOs = null;
            long start = System.currentTimeMillis();
            try {
 
                clientOs = clientSocket.getOutputStream();
                clientIs = clientSocket.getInputStream();
                targetOs = targetSocket.getOutputStream();
                targetIs = targetSocket.getInputStream();
 
                // 512K，因为会有很多个线程同时申请buff空间，所以不要太大以以防OOM
                byte[] buff = new byte[1024 * 512];
                while (true) {
 
                    boolean needSleep = true;
                    while (clientIs.available() != 0) {
                        int n = clientIs.read(buff);
                        targetOs.write(buff, 0, n);
                        transientLog("client to remote, bytes=%d", n);
                        needSleep = false;
                    }
 
                    while (targetIs.available() != 0) {
                        int n = targetIs.read(buff);
                        clientOs.write(buff, 0, n);
                        transientLog("remote to client, bytes=%d", n);
                        needSleep = false;
                    }
 
                    if (clientSocket.isClosed()) {
                        transientLog("client closed");
                        break;
                    }
 
                    // 会话最多30秒超时，防止有人占着线程老不释放
                    if (System.currentTimeMillis() - start > 30_000) {
                        transientLog("time out");
                        break;
                    }
 
                    // 如果本次循环没有数据传输，说明管道现在不繁忙，应该休息一下把资源让给别的线程
                    if (needSleep) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
 
                }
            } catch (IOException e) {
                transientLog("conn exception" + e.getMessage());
            } finally {
                close(clientIs);
                close(clientOs);
                close(targetIs);
                close(targetOs);
                close(clientSocket);
                close(targetSocket);
            }
            transientLog("done.");
        }
 
        private void transientLog(String format, Object... args) {
            log("forwarding, clientIp=" + clientIp + ", targetAddress=" + targetAddress + ", port=" + targetPort + ", " + format, args);
        }
 
    }
 
    
    
    
 
   
 
    private synchronized static void log(String format, Object... args) {
        System.out.println(String.format(format, args));
    }
 
    private static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
 
    public static void main(String[] args) throws IOException {
 
        ServerSocket serverSocket = new ServerSocket(SERVICE_LISTENER_PORT);
        while (true) {
            Socket socket = serverSocket.accept();
            if (clientNumCount.get() >= MAX_CLIENT_NUM) {
                log("client num run out.");
                continue;
            }
            log("new client, ip=%s:%d, current client count=%s", socket.getInetAddress(), socket.getPort(), clientNumCount.get());
            clientNumCount.incrementAndGet();
            new Thread(new ClientHandler(socket), "client-handler-" + UUID.randomUUID().toString()).start();
        }
 
    }
 
}