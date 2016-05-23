package com.example.metehan.synchronizedvpnsockets;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.LinkedList;

/**
 * Created by metehan on 3/29/2016.
 */
public class MyVpnService extends VpnService {
    private static final int IP_PACKET_MAX_LENGTH = 65535;
    private byte[] mResponseBuffer = new byte[IP_PACKET_MAX_LENGTH];
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    protected LinkedList<byte[]> packets = new LinkedList<>();
    //a. Configure a builder for the interface.
    Builder builder = new Builder();

    // Services interface
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // Start a new session by creating a new thread.
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println(Inet4Address.getByName("localhost"));
                    //a. Configure the TUN and get the interface.
                    mInterface = builder.setSession("MyVPNService")
                            .addAddress("192.168.0.1", 24)
                            .addDnsServer("8.8.8.8")
                            .addRoute("0.0.0.0", 0).establish();
                    //b. Packets to be sent are queued in this input stream.
                    final FileInputStream in = new FileInputStream(
                            mInterface.getFileDescriptor());
                    //b. Packets received need to be written to this output stream.
                    final FileOutputStream out = new FileOutputStream(
                            mInterface.getFileDescriptor());
                    //c. The UDP channel can be used to pass/get ip package to/from server
                    DatagramChannel tunnel = DatagramChannel.open();
                    // Connect to the server, localhost is used for demonstration only.
                    // InetAddress.getLocalHost() could be used instead of 127.0.0.1
                    tunnel.connect(new InetSocketAddress("127.0.0.1", 8087));
                    Log.e("Localhost: ", InetAddress.getLocalHost().toString());
                    //d. Protect this socket, so package send by it will not be feedback to the vpn service.
                    protect(tunnel.socket());
                    Log.e("Tunnel socket: ", String.valueOf(tunnel.socket().getPort()));
                    protect(8087);
                    // Allocate the buffer for a single packet
                    final ByteBuffer packet = ByteBuffer.allocate(IP_PACKET_MAX_LENGTH);
                    // We use a timer to determine the status of the tunnel. It
                    // works on both sides. A positive value means sending, and
                    // any other means receiving. We start with receiving.
                    int timer = 0;
                    int socketCounter = 0;
                    Thread.sleep(1000);
                    // Keep forwarding till something goes wrong
                    Runnable writeToOut = new Runnable() {
                        @Override
                        public void run() {
                            while (true) {
                                if (!packets.isEmpty()) {
                                    byte[] packetToWriteToOut = packets.remove();
                                    try {
                                        out.write(packetToWriteToOut, 0, packetToWriteToOut.length);
                                    } catch (Exception e) {
                                        System.out.println("Writing to out from queue failed");
                                    }
                                }
                            }
                        }
                    };
                    Thread writeToOutThread = new Thread(writeToOut);
                    writeToOutThread.start();
                    while (true) {
                        // Assume that we did not make any progress in this iteration.
                        boolean idle = true;
                        // Read the outgoing packet from the input stream.
                        int length = in.read(packet.array());
                        //debugPacket(packet);
                        if (length > 0) {
                            IPPacket mIPPacket = new IPPacket(packet.array());
                            int protocol = mIPPacket.getProtocol();
                            if (isTestDstAddress()) {
                                Log.d("MyVpnService: ", "DST port: " + mIPPacket.getDstPort() +
                                        " Transport-layer protocol: " +
                                        (protocol == IPPacket.TRANSPORT_PROTOCOL_TCP ? "TCP, flags: "
                                                + mIPPacket.getPacket()[mIPPacket.getIpHeaderLength()
                                                + IPPacket.TCP_FLAGS_INDEX] : "UDP")
                                        + " SRC: " + mIPPacket.getSrcIpAddressAsString()
                                        + " DST: " + mIPPacket.getDstIpAddressAsString());
                            }

                            switch (protocol) {
                                case IPPacket.TRANSPORT_PROTOCOL_TCP:
                                    Log.d("TCP:", " protocol packet");
                                    Socket protectedSocket = new Socket();
                                    protectedSocket.bind(null);
                                    if(protect(protectedSocket)) {
                                        TcpSocketThread mTcpThread = new TcpSocketThread(protectedSocket, mIPPacket);
                                        mTcpThread.start();
                                    }
                                    break;
                                case IPPacket.TRANSPORT_PROTOCOL_UDP:
                                    DatagramSocket datagramSocket = new DatagramSocket();
                                    if (protect(datagramSocket)) {
                                        UdpSocketThread mUdpThread
                                                = new UdpSocketThread(datagramSocket, mIPPacket, packet.array());
                                        mUdpThread.start();
                                    } else {
                                        throw new IllegalStateException("Failed to create a protected UDP socket");
                                    }
                                    break;
                            }
                        }
                        Thread.sleep(100);
                    }
                } catch (Exception e) {
                    // Catch any exception
                    e.printStackTrace();
                } finally {
                    try {
                        if (mInterface != null) {
                            mInterface.close();
                            mInterface = null;
                        }
                    } catch (Exception e) {

                    }
                }
            }

        }, "MyVpnRunnable");

        //start the service
        mThread.start();
        return START_STICKY;
    }

    class TcpSocketThread extends Thread{
        Socket sock;
        IPPacket packet;

        TcpSocketThread(Socket sock, IPPacket packet) {
            this.packet = packet;
            this.sock = sock;
        }

        @Override
        public void run() {
            try {
                sock.connect(new InetSocketAddress(InetAddress.getByAddress(packet.getDstIpAddress()),
                        packet.getDstPort()));
                InetAddress tcpPacketDstAddress
                        = InetAddress.getByAddress(packet.getDstIpAddress());
                SocketAddress tcpSocketDstAddress
                        = new InetSocketAddress(tcpPacketDstAddress, packet.getDstPort());
                // protectedSocket.connect(tcpSocketDstAddress, 200000);
                // protectedSocket.connect(new InetSocketAddress("216.58.193.100", 80));
                System.out.println("Connected to tcp server..."
                        + packet.getDstIpAddressAsString()
                        + packet.getDstPort());
                InputStream inTcp = sock.getInputStream();
                OutputStream outTcp = sock.getOutputStream();
                outTcp.write(packet.getPayload());
                packet.reset();
                byte[] tcpResponse = new byte[IP_PACKET_MAX_LENGTH];
                inTcp.read(tcpResponse, 0, tcpResponse.length);
                System.out.println(new String(tcpResponse));
                sock.close();
            } catch(Exception e) {
                System.out.println("Tcp socket thread failed");
            }
        }
    }

    class UdpSocketThread extends Thread{

        DatagramSocket sock;
        IPPacket packet;
        byte[] packetArray;

        UdpSocketThread(DatagramSocket sock, IPPacket packet, byte[] packetArray) {
            this.packet = packet;
            this.sock = sock;
            this.packetArray = packetArray;
        }

        @Override
        public void run() {
            try {
                if (isTestDstAddress()) {
                    byte[] sentPacket = Arrays.copyOfRange(packetArray, 0, packet.getTotalLength());
                    Log.d("MyVpnService: ", "UDP. Sent packet == " + Arrays.toString(sentPacket));
                }
                DatagramPacket request = new DatagramPacket(packet.getPayload(),
                        packet.getPayload().length,
                        Inet4Address.getByAddress(packet.getDstIpAddress()),
                        packet.getDstPort());
                sock.send(request);
                byte[] responseBuffer = new byte[IP_PACKET_MAX_LENGTH];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                sock.receive(responsePacket);
                packet.swapIpAddresses();
                byte[] responseData = Arrays.copyOfRange(responseBuffer, 0, responsePacket.getLength());
                if (isTestSrcAddress()) {
                    Log.d("MyVpnService: ", "UDP response: " + new String(responseData));
                }
                int ipHeader = packet.getIpHeaderLength();
                int transportHeader = packet.getTransportLayerHeaderLength();
                int headerLengths = ipHeader + transportHeader;
                packet.setTotalLength(headerLengths + responseData.length);
                packet.setPayload(headerLengths, responseData);
                                /*
                                 * Before computing the checksum of the IP header:
                                 *
                                 * 1. Swap IP addresses.
                                 * 2. Calculate the total length.
                                 * 3. Identification (later)
                                 */
                packet.calculateIpHeaderCheckSum();

                packet.swapPortNumbers();
                packet.setUdpHeaderAndDataLength(transportHeader + responseData.length);
                                /*
                                 * Before computing the checksum of the UDP header and data:
                                 * 1. Swap the port numbers.
                                 * 2. Set the response data (setPayload).
                                 * 3. Set the UDP header and data length.
                                 */
                packet.updateUdpCheckSum();
                if (isTestSrcAddress()) {
                    byte[] toTun = Arrays.copyOfRange(packet.getPacket(), 0, packet.getTotalLength());
                    Log.d("MyVpnService: ", "To TUN == " + Arrays.toString(toTun));
                }
                synchronized (packets){
                    packets.add(packet.getPacket());
                }
            } catch (IOException e) {
                Log.e("MyVpnService: ", "", e);
            } finally {
                sock.close();
            }
        }
    }

    private void debugPacket(ByteBuffer packet) {
        int buffer = packet.get();
        int version;
        int headerlength;
        version = buffer >> 4;
        headerlength = buffer & 0x0F;
        headerlength *= 4;
        Log.d("Debug Packet", "IP Version:" + version);
        Log.d("Debug Packet", "Header Length:" + headerlength);

        String status = "";
        status += "Header Length:" + headerlength;

        buffer = packet.get();      //DSCP + EN
        buffer = packet.getChar();  //Total Length

        Log.d("Debug Packet", "Total Length:" + buffer);

        buffer = packet.getChar();  //Identification
        buffer = packet.getChar();  //Flags + Fragment Offset
        buffer = packet.get();      //Time to Live
        buffer = packet.get();      //Protocol

        Log.d("Debug Packet", "Protocol:" + buffer);

        status += "  Protocol:" + buffer;

        buffer = packet.getChar();  //Header checksum

        String sourceIP = "";
        buffer = packet.get();  //Source IP 1st Octet
        sourceIP += buffer & 0xFF;
        sourceIP += ".";

        buffer = packet.get();  //Source IP 2nd Octet
        sourceIP += buffer & 0xFF;
        sourceIP += ".";

        buffer = packet.get();  //Source IP 3rd Octet
        sourceIP += buffer & 0xFF;
        sourceIP += ".";

        buffer = packet.get();  //Source IP 4th Octet
        sourceIP += buffer & 0xFF;

        Log.d("Debug Packet", "Source IP:" + sourceIP);

        status += "   Source IP:" + sourceIP;

        String destIP = "";
        buffer = packet.get();  //Destination IP 1st Octet
        destIP += buffer & 0xFF;
        destIP += ".";

        buffer = packet.get();  //Destination IP 2nd Octet
        destIP += buffer & 0xFF;
        destIP += ".";

        buffer = packet.get();  //Destination IP 3rd Octet
        destIP += buffer & 0xFF;
        destIP += ".";

        buffer = packet.get();  //Destination IP 4th Octet
        destIP += buffer & 0xFF;

        Log.d("Debug Packet", "Destination IP:" + destIP);

        status += "   Destination IP:" + destIP;
        /*
        msgObj = mHandler.obtainMessage();
        msgObj.obj = status;
        mHandler.sendMessage(msgObj);
        */

        //Log.d(TAG, "version:"+packet.getInt());
        //Log.d(TAG, "version:"+packet.getInt());
        //Log.d(TAG, "version:"+packet.getInt());

    }

    private static boolean isTestDstAddress() {
        return true;
        //return IPPacket.PACKET.getDstIpAddressAsString().equals("192.168.1.197");
    }

    static boolean isTestSrcAddress() {
        return true;
        //return IPPacket.PACKET.getSrcIpAddressAsString().equals("192.168.1.197");
    }

    @Override
    public void onDestroy() {
        // TODO Auto-generated method stub
        if (mThread != null) {
            mThread.interrupt();
        }
        super.onDestroy();
    }
}
