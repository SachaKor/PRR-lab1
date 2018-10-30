package protocol;

/**
 * This class contains constants for the communication protocol between the master and the slaves
 * Defines all the commands which are sent and received by the master and the slaves
 * These commands are sent as integers (enumeration's ordinals)
 *
 * @author Samuel Mayor, Alexandra Korukova
 */
public enum Protocol {
    SYNC("SYNC"),
    FOLLOW_UP("FOLLOW_UP"),
    DELAY_REQUEST("DELAY_REQUEST"),
    DELAY_RESPONSE("DELAY_RESPONSE");

    private final String message;

    Protocol(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
