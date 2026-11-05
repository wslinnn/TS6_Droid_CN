package dev.tslib;

/**
 * A TeamSpeak channel (immutable snapshot).
 */
public class Channel {
    public final long id;
    public final long parentId;
    public final String name;
    public final String topic;
    public final String description;
    /**
     * Predecessor channel id in the server's sort chain (0 = first channel
     * of the sibling list). Not a numeric sort key — build the sibling order
     * by following the chain (see {@link ChannelTree}).
     */
    public final long order;
    public final boolean isPermanent;
    public final boolean isSemiPermanent;
    public final boolean isDefault;
    public final boolean hasPassword;
    public final byte codec;
    public final byte codecQuality;
    public final int maxClients;
    public final int maxFamilyClients;
    public final int neededTalkPower;
    public final long iconId;
    public final long permissionHints;
    /**
     * True when {@link #permissionHints} came from the server or a successful
     * permission query. When false, zero bits mean "unknown", not "denied".
     */
    public final boolean permissionHintsKnown;

    // Permission hint bit flags
    public static final long PERM_FILE_UPLOAD = 64;
    public static final long PERM_FILE_DOWNLOAD = 128;
    public static final long PERM_FILE_DELETE = 256;
    public static final long PERM_FILE_RENAME = 512;
    public static final long PERM_FILE_BROWSE = 1024;
    public static final long PERM_FILE_DIRECTORY_CREATE = 2048;

    public Channel(long id, long parentId, String name, String topic,
                   String description, long order, boolean isPermanent,
                   boolean isSemiPermanent, boolean isDefault,
                   boolean hasPassword, byte codec, byte codecQuality,
                   int maxClients, int maxFamilyClients,
                   int neededTalkPower, long iconId, long permissionHints,
                   boolean permissionHintsKnown) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
        this.topic = topic;
        this.description = description;
        this.order = order;
        this.isPermanent = isPermanent;
        this.isSemiPermanent = isSemiPermanent;
        this.isDefault = isDefault;
        this.hasPassword = hasPassword;
        this.codec = codec;
        this.codecQuality = codecQuality;
        this.maxClients = maxClients;
        this.maxFamilyClients = maxFamilyClients;
        this.neededTalkPower = neededTalkPower;
        this.iconId = iconId;
        this.permissionHints = permissionHints;
        this.permissionHintsKnown = permissionHintsKnown;
    }

    public boolean canUploadFile() {
        return permissionHintsKnown && (permissionHints & PERM_FILE_UPLOAD) != 0;
    }

    public boolean canDownloadFile() {
        return !permissionHintsKnown || (permissionHints & PERM_FILE_DOWNLOAD) != 0;
    }

    public boolean canDeleteFile() {
        return permissionHintsKnown && (permissionHints & PERM_FILE_DELETE) != 0;
    }

    public boolean canRenameFile() {
        return permissionHintsKnown && (permissionHints & PERM_FILE_RENAME) != 0;
    }

    public boolean canBrowseFiles() {
        return !permissionHintsKnown || (permissionHints & PERM_FILE_BROWSE) != 0;
    }

    public boolean canCreateDirectory() {
        return permissionHintsKnown && (permissionHints & PERM_FILE_DIRECTORY_CREATE) != 0;
    }

    @Override
    public String toString() {
        return "Channel(id=" + id + ", name='" + name + "')";
    }
}
