package in.otrail;

/** Editable offline-routing defaults. Routing graphs are downloaded to the phone, never bundled. */
public final class RoutingConfig {
  private RoutingConfig() {}
  public static final String BROUTER_TILE_BASE_URL="https://brouter.de/brouter/segments4/";
  // Inclusive 5-degree coverage rectangle includes India's islands and frontier regions.
  // Whole provider tiles also contain roads in neighbouring countries.
  public static final int MIN_LATITUDE=5, MAX_LATITUDE=35;
  public static final int MIN_LONGITUDE=65, MAX_LONGITUDE=95, TILE_DEGREES=5;
  public static final String DOWNLOAD_BUTTON="Download missing India routing data";
  public static final String ADVANCED_BUTTON="Advanced · tile updates, imports & Valhalla";
  public static final String COVERAGE_NOTE="One download prepares BRouter road routing for India, including islands and border tiles (which also contain neighbouring roads). Files stay on your phone, separately from map display data. Wi-Fi and cellular supported; roaming disabled. This can require several GB; Android reports exact sizes once queued.";
}
