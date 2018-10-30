package master;

import protocol.Protocol;

import java.io.*;
import java.net.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class represents the master for the Precision Time Protocol.
 * Sends the SYNC and FOLLOW_UP messages and handles DELAY_REQUESTs.
 *
 * @author Samuel Mayor, Alexandra Korukova
 *
 * Description:
 * Master is implemented with two threads:
 * - one thread sending the SYNC and FOLLOW_UP messages: {@link SyncSender}
 * - one thread listening to DELAY_REQUESTs and replying with DELAY_RESPONSEs: {@link DelayRequestListener}
 * Both of these threads implement {@link Runnable} interface and are launched in the {@link Server} class.
 * SYNC and FOLLOW_UP messages are sent to the multicast address via a {@link MulticastSocket}
 * DELAY_RESPONSEs are sent to the DELAY_REQUEST sender via a {@link DatagramSocket}
 * To receive and send data in the UDP packets, {@link ByteArrayInputStream}s and {@link ByteArrayOutputStream} wrapped
 * into the {@link DataInputStream} and {@link DataOutputStream} are used.
 */
public class Server {

    private static final Logger LOG = Logger.getLogger(Server.class.getName());

    // SYNC and FOLLOW_UP messages will be sent every timeInterval milliseconds
    private int timeInterval = 2000;
    // network group for SYNC messages
    private InetAddress group;
    // buffer size for receiving packets
    private static final int BUFFER_SIZE = 256;

    /**
     * Constructor
     * @param group network group
     * @param timeInterval interval defining the time elapsed between two SYNC commands
     */
    public Server(InetAddress group, int timeInterval) {
        this.timeInterval = timeInterval;
        this.group = group;
    }

    /**
     * Default constructor
     */
    public Server() {
        try {
            group = InetAddress.getByName("228.5.6.7");
        } catch (UnknownHostException e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    public void start() {
        new Thread(new SyncSender()).start();
        new Thread(new DelayRequestListener()).start();
    }

    /**
     * {@link SyncSender} class sends the SYNC and FOLLOW_UP messages every timeInterval milliseconds
     * Works in a separate thread
     */
    private class SyncSender implements Runnable {

        // id of the SYNC command, generated by SyncSender
        private long id = 0;
        private boolean shouldRun = true;
        private int port;

        private MulticastSocket socket;

        /**
         * Constructor
         * @param port port of the {@link SyncSender}
         */
        SyncSender(int port) {
            this.port = port;
            try {
                socket = new MulticastSocket(port);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
            }
        }

        /**
         * Default constructor
         * The port is set to 4445
         */
        SyncSender() {
            this(4445);
        }

        @Override
        public void run() {
            LOG.log(Level.INFO, () -> Protocol.SYNC.getMessage() + " commands will be sent to " + group);
            byte[] buffer;
            DatagramPacket packet;
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
            while (shouldRun) {
                try {
                    // sending the SYNC message and id
                    dataOutputStream.writeInt(Protocol.SYNC.ordinal());
                    dataOutputStream.writeLong(id);
                    buffer = byteArrayOutputStream.toByteArray();
                    packet = new DatagramPacket(buffer, buffer.length, group, port);
                    long masterTime = System.currentTimeMillis();
                    socket.send(packet);
                    LOG.log(Level.INFO, () -> "[" + id + "] MASTER TIME: " + masterTime);
//                    LOG.log(Level.INFO, () -> "[" + id + "] " + Protocol.SYNC.getMessage() + " sent");
                    byteArrayOutputStream.reset();
                    // sending the FOLLOW_UP message and the current time
                    dataOutputStream.writeInt(Protocol.FOLLOW_UP.ordinal());
                    dataOutputStream.writeLong(id);
                    dataOutputStream.writeLong(masterTime);
                    buffer = byteArrayOutputStream.toByteArray();
                    packet = new DatagramPacket(buffer, buffer.length, group, port);
                    socket.send(packet);
//                    LOG.log(Level.INFO, () -> "[" + id + "] " + Protocol.FOLLOW_UP.getMessage() + " sent");
                    id++;
                    byteArrayOutputStream.reset();
                    // wait timeInterval milliseconds
                    Thread.sleep(timeInterval);
                } catch (InterruptedException | IOException e) {
                    socket.close();
                    LOG.log(Level.SEVERE, e.getMessage(), e);
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * {@link DelayRequestListener} class accepts and responds the delay requests
     * Works in a separate thread
     */
    private class DelayRequestListener implements Runnable {

        private boolean shouldRun = true;
        private DatagramSocket socket;

        DelayRequestListener(int port) {
            try {
                socket = new DatagramSocket(port);
            } catch (SocketException e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
            }
        }

        /**
         * Default constructor
         * The port is set to 4446
         */
        DelayRequestListener() {
            this(4446);
        }


        @Override
        public void run() {
            LOG.log(Level.INFO, "starting to wait for " + Protocol.DELAY_REQUEST + "s");
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            ByteArrayInputStream byteArrayInputStream;
            DataInputStream dataInputStream;
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
            while (shouldRun) {
                try {
                    // listening for DELAY_REQUESTs
                    socket.receive(packet);
                    buffer = packet.getData();
                    byteArrayInputStream = new ByteArrayInputStream(buffer);
                    dataInputStream = new DataInputStream(byteArrayInputStream);
                    int request = dataInputStream.readInt();
                    if (request == Protocol.DELAY_REQUEST.ordinal()) {
                        long id = dataInputStream.readLong();
//                        LOG.log(Level.INFO, () -> "[" + id + "] " + Protocol.DELAY_REQUEST.getMessage() + " received");
                        InetAddress clientAddress = packet.getAddress();
                        int clientPort = packet.getPort();
                        dataOutputStream.writeInt(Protocol.DELAY_RESPONSE.ordinal());
                        dataOutputStream.writeLong(id);
                        dataOutputStream.writeLong(System.currentTimeMillis());
                        byte[] response = byteArrayOutputStream.toByteArray();
                        packet = new DatagramPacket(response, response.length, clientAddress, clientPort);
                        socket.send(packet);
                        byteArrayOutputStream.reset();
//                        LOG.log(Level.INFO, () -> "[" + id + "] " + Protocol.DELAY_RESPONSE.getMessage() + " sent");
                    } else {
                        Logger.getLogger(getClass().getName()).log(Level.SEVERE, () -> "Unknown "
                                + Protocol.DELAY_REQUEST.getMessage());
                    }
                } catch (IOException e) {
                    socket.close();
                    LOG.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        }
    }

    public static void main(String[] args) {
        new Server().start();
    }

}