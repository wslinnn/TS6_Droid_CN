package dev.tslib;

import java.util.Objects;

/**
 * Server information (immutable snapshot).
 */
public class ServerInfo {
    public final String name;
    public final String platform;
    public final String version;
    public final int maxClients;
    public final int clientsOnline;
    public final int channelsOnline;
    public final long uptime;
    public final String welcomeMessage;
    public final long iconId;

    public ServerInfo(String name, String platform, String version,
                      int maxClients, int clientsOnline, int channelsOnline,
                      long uptime, String welcomeMessage, long iconId) {
        this.name = name;
        this.platform = platform;
        this.version = version;
        this.maxClients = maxClients;
        this.clientsOnline = clientsOnline;
        this.channelsOnline = channelsOnline;
        this.uptime = uptime;
        this.welcomeMessage = welcomeMessage;
        this.iconId = iconId;
    }

    public ServerInfo(String name, String platform, String version,
                      int maxClients, int clientsOnline, int channelsOnline,
                      long uptime, String welcomeMessage) {
        this(name, platform, version, maxClients, clientsOnline,
             channelsOnline, uptime, welcomeMessage, 0L);
    }

    public ServerInfo(String name, String platform, String version,
                      int maxClients, int clientsOnline,
                      String welcomeMessage, long iconId) {
        this(name, platform, version, maxClients, clientsOnline,
             0, 0L, welcomeMessage, iconId);
    }

    public ServerInfo(String name, String platform, String version,
                      int maxClients, int clientsOnline,
                      String welcomeMessage) {
        this(name, platform, version, maxClients, clientsOnline,
             welcomeMessage, 0L);
    }

    /**
     * Value semantics: the native snapshot bridge rebuilds this object on
     * every refresh, so without equals/hashCode every assignment to the
     * StateFlow counts as a change.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ServerInfo)) return false;
        ServerInfo other = (ServerInfo) o;
        return maxClients == other.maxClients
            && clientsOnline == other.clientsOnline
            && channelsOnline == other.channelsOnline
            && uptime == other.uptime
            && iconId == other.iconId
            && Objects.equals(name, other.name)
            && Objects.equals(platform, other.platform)
            && Objects.equals(version, other.version)
            && Objects.equals(welcomeMessage, other.welcomeMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, platform, version, maxClients, clientsOnline,
            channelsOnline, uptime, welcomeMessage, iconId);
    }

    @Override
    public String toString() {
        return "ServerInfo(name='" + name + "')";
    }
}
