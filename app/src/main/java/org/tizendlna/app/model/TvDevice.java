package org.tizendlna.app.model;

import java.util.Locale;

public final class TvDevice {
    public final String udn;
    public final String friendlyName;
    public final String manufacturer;
    public final String modelName;
    public final String hostIp;
    public final String avTransportControlUrl;
    public final String renderingControlUrl;
    public final boolean isSamsung;

    public TvDevice(
            String udn,
            String friendlyName,
            String manufacturer,
            String modelName,
            String hostIp,
            String avTransportControlUrl,
            String renderingControlUrl
    ) {
        this.udn = udn != null ? udn : avTransportControlUrl;
        this.friendlyName = (friendlyName != null && !friendlyName.isEmpty()) ? friendlyName : hostIp;
        this.manufacturer = manufacturer != null ? manufacturer : "";
        this.modelName = modelName != null ? modelName : "";
        this.hostIp = hostIp != null ? hostIp : "";
        this.avTransportControlUrl = avTransportControlUrl;
        this.renderingControlUrl = renderingControlUrl;
        String m = (this.manufacturer + " " + this.friendlyName + " " + this.modelName).toLowerCase(Locale.ROOT);
        this.isSamsung = m.contains("samsung") || m.contains("tizen");
    }

    public String displayLabel() {
        if (!modelName.isEmpty() && !friendlyName.contains(modelName)) {
            return friendlyName + " (" + modelName + ")";
        }
        return friendlyName;
    }
}
