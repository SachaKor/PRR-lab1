package slave;

import protocol.Protocol;

import java.io.*;
import java.net.*;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class represents a slave of the Precision Time Protocol.
 * Receives SYNC and FOLLOW_UP messages and sends the DELAY_REQUESTs
 *
 * @author Samuel Mayor, Alexandra Korukova
 *
 * Description:
 * Slave is implemented with two threads:
 * - one thread receiving the SYNC and FOLLOW_UP messages and calculting the time gap between the master and slave:
 * {@link SyncListener}
 * - one thread sending the DELAY_REQUESTs and receiving the DELAY_RESPONSEs in order to calculate the time delay
 * between the master and the slave: {@link DelayRequestSender}
 * Both of these threads implement {@link Runnable} interface. {@link SyncListener} is launched in the {@link Client}
 * class when its start method is called. {@link DelayRequestSender} is launched from the {@link SyncListener} class
 * once the first FOLLOW_UP message containing the master's time is received.
 * Slave's local time is calculated every time the FOLLOW_UP message is received once the first delay is calculated.
 * To receive and send data in the UDP packets, {@link ByteArrayInputStream}s and {@link ByteArrayOutputStream} wrapped
 * into the {@link DataInputStream} and {@link DataOutputStream} are used.
 */
public class Client {

    private static final Logger LOG = Logger.getLogger(Client.class.getName());

    private InetAddress group;
    private static final int BUFFER_SIZE = 256;
    private int timeInterval = 2000;
    private boolean firstDelayCalculated = false;
    private long gap;
    private long delay;

    /**
     * Constructor
     * @param address multicast group for the SYNC messages
     * @param timeInterval the time interval within which the SYNC messages are sent
     */
    public Client(InetAddress address, int timeInterval) {
        this.group = address;
        this.timeInterval = timeInterval;
    }

    /**
     * Default constructor
     */
    public Client() {
        try {
            group = InetAddress.getByName("228.5.6.7");
        } catch (UnknownHostException e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    /**
     * Launches the slave
     */
    public void start() {
        new Thread(new SyncListener()).start();
    }


    /**
     * {@link SyncListener} class receives the the SYNC and FOLLOW_UP messages and calculates
     * the time gap between the master and the slave
     */
    private class SyncListener implements Runnable {

        private MulticastSocket socket;
        private long masterTime;
        private boolean shouldRun = true;
        private long syncId = -1;
        private int port = 4445;

        /**
         * Constructor
         */
        SyncListener() {
            try {
                socket = new MulticastSocket(port);
                socket.joinGroup(group);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
            }

        }

        @Override
        public void run() {
            boolean firstFollowUpReceived = false;
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            ByteArrayInputStream byteArrayInputStream;
            DataInputStream dataInputStream;
            long systemTime = 0;
            while (shouldRun) {
                try {
                    // listen fot the incoming package
                    socket.receive(packet);
                    buffer = packet.getData();
                    byteArrayInputStream = new ByteArrayInputStream(buffer);
                    dataInputStream = new DataInputStream(byteArrayInputStream);
                    int commandNumber = dataInputStream.readInt();
                    if (commandNumber == Protocol.SYNC.ordinal()) { // receiving SYNC message
                        syncId = dataInputStream.readLong();
                        systemTime = System.currentTimeMillis();
//                        LOG.log(Level.INFO, () -> "[" + syncId + "] " + Protocol.SYNC.getMessage() + " received");
                    } else if (commandNumber == Protocol.FOLLOW_UP.ordinal()) { // receiving FOLLOW_UP message
                        long fuId = dataInputStream.readLong();
//                        LOG.log(Level.INFO, () -> "[" + fuId + "] " +  Protocol.SYNC.getMessage() + " received");
                        if(fuId == syncId) { // id check
                            masterTime = dataInputStream.readLong();
                            gap = masterTime - systemTime;
                            LOG.log(Level.INFO, () -> "[" + syncId + "] gap: " + gap);
                            // the thread which sends the DELAY_REQUESTs is launched after the first
                            // FOLLOW_UP message is received
                            if (!firstFollowUpReceived) {
                                firstFollowUpReceived = true;
                                new Thread(new DelayRequestSender(packet.getAddress())).start();
                            }
                            // the local time is displayed after the first delay is calculated
                            if (firstDelayCalculated) {
                                long resultTime = systemTime + gap + delay;
                                LOG.log(Level.INFO, () -> "[" + syncId + "] LOCAL TIME: " + resultTime + "\n");
                            }
                        }
                    } else {
                        LOG.log(Level.SEVERE, () -> "Unknown " + Protocol.SYNC.getMessage() + " command : "
                                + commandNumber);
                    }
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, e.getMessage(), e);
                    try {
                        socket.leaveGroup(group);
                        socket.close();
                    } catch (IOException e1) {
                        LOG.log(Level.SEVERE, e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * {@link DelayRequestSender} class sends DELAY_REQUESTs to the master to calculate the time delay between
     * itself and the master
     */
    private class DelayRequestSender implements Runnable {

        private boolean shouldRun = true;
        private Random random;
        private DatagramSocket socket;
        private InetAddress serverAddress;
        private int port = 4446;

        private long delayId = 0;

        /**
         * Constructor
         */
        DelayRequestSender(InetAddress serverAddress) {
            random = new Random();
            try {
                socket = new DatagramSocket();
                this.serverAddress = serverAddress;
            } catch (SocketException e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
            }
        }

        @Override
        public void run() {
            LOG.log(Level.INFO, this.getClass().getName() + " launched");
            long localTimeOfDelayRequest;
            byte[] buffer;
            DatagramPacket packet;
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
            ByteArrayInputStream byteArrayInputStream;
            DataInputStream dataInputStream;
            while (shouldRun) {
                try {
                    // send the DELAY_REQUEST
                    dataOutputStream.writeInt(Protocol.DELAY_REQUEST.ordinal());
                    dataOutputStream.writeLong(delayId);
                    buffer = byteArrayOutputStream.toByteArray();
                    packet = new DatagramPacket(buffer, buffer.length, serverAddress, port);
                    localTimeOfDelayRequest = System.currentTimeMillis() + gap + delay;
                    socket.send(packet);
                    byteArrayOutputStream.reset();
//                    LOG.log(Level.INFO, () -> "[" + delayId + "] " + Protocol.DELAY_REQUEST + " sent");
                    // block until the DELAY_RESPONSE is received
                    buffer = new byte[BUFFER_SIZE];
                    packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    buffer = packet.getData();
                    byteArrayInputStream = new ByteArrayInputStream(buffer);
                    dataInputStream = new DataInputStream(byteArrayInputStream);
                    int commandNumber = dataInputStream.readInt();
                    if (commandNumber == Protocol.DELAY_RESPONSE.ordinal()) { // command verification
                        long drId = dataInputStream.readLong();
//                        LOG.log(Level.INFO, () -> "[" + drId + "] " + Protocol.DELAY_RESPONSE + " received");
                        if (drId == delayId) { // id verification
                            long masterTime = dataInputStream.readLong();
                            delay = (masterTime - localTimeOfDelayRequest)/2;
                            LOG.log(Level.INFO, () -> "[" + delayId +"] delay: " + delay);
                            firstDelayCalculated = true;
                        }
                    } else {
                        LOG.log(Level.SEVERE, "Unknown " + Protocol.DELAY_RESPONSE.getMessage());
                    }
                    delayId++;
                    Thread.sleep((long) 4*timeInterval + random.nextInt(60*timeInterval-4*timeInterval+1));
                } catch (InterruptedException | IOException e) {
                    LOG.log(Level.SEVERE, e.getMessage(), e);
                    socket.close();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public static void main(String[] args) {
        new Client().start();
    }
}
