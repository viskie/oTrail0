package in.otrail;

import java.util.Locale;
import java.util.Map;

/** Edit this file and rebuild. ARGB colors are 0xAARRGGBB; text sizes are sp.
 * Source journal, routing algorithms and downloaded map data are independent of these settings.
 */
public class UiConfig {
  protected UiConfig() {}
  public static final int BLUE=0xff0078ff,PURPLE=0xffbd00ff,ORANGE=0xffff9a00,LIME=0xff01ff1f,YELLOW=0xffe3ff00;
  public static final int ACCENT_TEXT=0xff004fa8,HEADING=0xffff4162,SECONDARY=0xffFF00FF;
  public static final int BUTTON=0xff17deee,BUTTON_TEXT=0xff01ff1f;
  public static final int BACKGROUND=0xff000000,SURFACE=0xffe7e7eb,INK=0xff17deee,MUTED=0xffff7f50;
  public static final int CYAN=0xff17deee,CORAL=0xffff7f50,PINK=0xffff4162,LEMON=0xffecf284,TEAL=0xff10aeb2, BLACK=0xff000000;
  // Shared neon border palettes; button typography and ripple colors remain separate.
  public static final int NEON_CURRENT=0xff00eaff, NEON_LIME=LIME,
      NEON_BRIGHT_ORANGE=0xffff9d00, NEON_BRIGHT_YELLOW=0xffffff00;
  public static final int[] NEON_CURRENT_STOPS={0x3300a9c8,0x7700c9e7,NEON_CURRENT,0xff00bce5};
  public static final int[] NEON_INTERIOR={0xff080d13,0xff020508,0xff000000};
  public static final int NEON_CURRENT_REFLECTION=0x9900ffff, NEON_INNER_TOP_ALPHA=235;
  public static final float NEON_INNER_TOP_WIDTH_DP=1f;
  public static final int BUTTON_BORDER=0xff17deee, BUTTON_RIPPLE=0xff17deee,
      BUTTON_DISABLED_TEXT=0x99ff00ff;
  // Vibrant finish. Existing manually edited base colors above remain authoritative.
  // Set VIBRANT_FINISH=false to return to flat controls; all colors remain editable here.
  public static final boolean VIBRANT_FINISH=true;
  public static final int BUTTON_GRADIENT_ACCENT=PURPLE, BUTTON_EDGE_ACCENT=LIME;
  public static final float BUTTON_GRADIENT_MIX=.24f, PANEL_TINT_MIX=.26f;
  public static final int BUTTON_FONT_STYLE=android.graphics.Typeface.BOLD_ITALIC;
  public static final boolean PSYCHEDELIC_GRADIENTS=true;
  public static final int GRADIENT_BRIGHT_ORANGE=0xffffb000;
  public static final int[] BUTTON_SPECTRUM={BUTTON,LIME,YELLOW,GRADIENT_BRIGHT_ORANGE};
  public static final int[] CANVAS_SPECTRUM={CYAN,SECONDARY,PURPLE,GRADIENT_BRIGHT_ORANGE};
  public static final float CANVAS_COLOR_STRENGTH=.62f;
  public static final int BODY_FONT_STYLE=android.graphics.Typeface.ITALIC,
      STATUS_FONT_STYLE=android.graphics.Typeface.BOLD_ITALIC;
  public static final String HEADING_FONT="casual";
  public static final int HEADING_FONT_STYLE=android.graphics.Typeface.BOLD_ITALIC;
  public static final int[] PANEL_ACCENTS={CYAN,PURPLE,ORANGE,LIME,PINK};
  public static final String BODY_FONT="sans-serif-condensed", BUTTON_FONT="casual",
      STATUS_FONT="sans-serif-medium", MAP_LABEL_FONT="sans-serif";
  public static final int BUTTON_SIZE_SP=12, BODY_SIZE_SP=10, STATUS_SIZE_SP=13,
      NAV_TITLE_SIZE_SP=19, NAV_INSTRUCTION_SIZE_SP=15, NAV_SUMMARY_SIZE_SP=12,
      MAP_LABEL_SIZE_SP=11, MAP_LABEL_STYLE=android.graphics.Typeface.BOLD; // 0 normal, 1 bold, 2 italic
  public static final int BUTTON_INNER_PADDING_DP=4, BUTTON_GAP_DP=4, CONTROL_ROW_PADDING_DP=2;
  public static final int SIDEBAR_WIDTH_DP=248, SIDEBAR_INSET_DP=6,
      PANEL_GAP_DP=4, PANEL_INNER_PADDING_DP=6;
  public static final int SETTINGS_COLUMNS_LANDSCAPE=3;
  public static final float LOCATE_VISUAL_SCALE=.60f;
  public static final int LOCATE_BEVEL_LIGHT=0xff656180, LOCATE_BEVEL_SHADOW=0xff170d25;
  public static final boolean NAV_TITLE_BOLD=true;
  public static final int LIVE_TRAIL_COLOR=0xffff1493;
  public static final int LOCATE_SIZE_DP=56, LOCATE_BACKGROUND=0xff000000,
      LOCATE_LOGO_COLOR=0xff00ffff, LOCATE_BORDER_COLOR=0xffff00ff, LOCATE_RIPPLE_COLOR=0x6640e0d0;
  public static final int STATUS_BACKGROUND=0xff000000, STATUS_TEXT_COLOR=0xff00ffff, STATUS_SPEED_COLOR=0xffffffff;
  public static final int NAV_PANEL_BACKGROUND=0xff000000;
  public static final int NAV_TEXT_COLOR=0xff40e0d0; // Solid turquoise navigation text.
  public static final int[] NAV_TEXT_SPECTRUM={0xffbf40ff,0xffff00ff,0xffb366ff,0xff00ffff,0xff40e0d0};
  public static final int MAP_SELECTOR_SIZE_SP=BODY_SIZE_SP+2;
  public static final int TOTAL_STATS_TEXT_COLOR=0xff01ff1f, TOTAL_STATS_BACKGROUND=0xff202039;
  public static final int TOTAL_STATS_FONT_STYLE=android.graphics.Typeface.BOLD_ITALIC;
  public static final int RESONATIONS_TITLE_SP=12, RESONATIONS_TEXT_SP=11, RESONATIONS_TEXT=0xff00ffff;
  public static final float TEXT_SCALE=1f;
  public static final int CAPTION_SIZE_SP=11, SECONDARY_SIZE_SP=12, MODE_SIZE_SP=13,
      SUBHEADING_SIZE_SP=14, HEADING_SIZE_SP=18, CARD_TITLE_SIZE_SP=19,
      SETTINGS_TITLE_SIZE_SP=25, APP_TITLE_SIZE_SP=27;
  public static final float OFFLINE_MAP_TEXT_SCALE=1f;
  // Offline basemap names such as Bilaspur (cities, towns, villages and suburbs).
  // Mapsforge families: default, sans_serif, serif, monospace.
  // Styles: normal, bold, italic, bold_italic. Size multiplier preserves zoom rules.
  public static final String CITY_LABEL_FONT="sans_serif", CITY_LABEL_STYLE="bold_italic";
  public static final int CITY_LABEL_COLOR=0xffFF00FF, CITY_LABEL_OUTLINE_COLOR=0xffFF00FF;
  public static final float CITY_LABEL_SIZE_SCALE=1f, CITY_LABEL_OUTLINE_WIDTH=.35f;
  // Theme pixels before screen density; city names get progressively larger with zoom.
  public static final float CITY_LABEL_MIN_SIZE=8f, CITY_LABEL_MAX_SIZE=14f;
  public static final int BIG_CITY_LABEL_COLOR=0xffff0000, LONG_STOP_COLOR=0xffff0000,
      LONG_STOP_LABEL_BACKGROUND=0xeefaf6ff;
  public static final String BIG_CITY_LABEL_STYLE="italic";
  public static final int LONG_STOP_MINUTES=60, LONG_STOP_LABEL_SIZE_SP=11,
      LONG_STOP_LABEL_MIN_ZOOM=8, LONG_STOP_LABEL_LIMIT=1;
  // Remaining offline basemap lettering (roads and POIs); INK controls default dark text.
  public static final int MAP_ROAD_LABEL_COLOR=INK, MAP_OTHER_LABEL_COLOR=INK,
      MAP_TEXT_OUTLINE_COLOR=0xffffffff;
  public static final String MAP_TEXT_FONT="sans_serif";
  public static float textSize(int originalSp) {
    int configured=switch(originalSp) {
      case 10 -> CAPTION_SIZE_SP; case 11 -> SECONDARY_SIZE_SP; case 12 -> BODY_SIZE_SP;
      case 13 -> MODE_SIZE_SP; case 14 -> SUBHEADING_SIZE_SP; case 15 -> STATUS_SIZE_SP;
      case 18 -> HEADING_SIZE_SP; case 19 -> CARD_TITLE_SIZE_SP;
      case 25 -> SETTINGS_TITLE_SIZE_SP; case 27 -> APP_TITLE_SIZE_SP;
      default -> originalSp;
    };
    return configured * TEXT_SCALE;
  }
  public static final int MAP_LABEL_BACKGROUND=ORANGE, MAP_LABEL_TEXT=INK,
      NAV_ROUTE_COLOR=CORAL;
  public static final int CONTINUITY=0xFF00FBFF;
  // First five: Vibrant Life (color-hex 5338). Remaining 26: Siter retro palettes.
  public static final int[] TRAIL_COLORS = {
      0xff10aeb2, 0xffff4162, 0xffff9a00, 0xffecf284, 0xffFF00FF,
      0xff3e2a18, 0xff8a5a2b, 0xffc1651e, 0xffd9a441, 0xffefe0bf,
      0xffc9a227, 0xffefe7c8, 0xff2a4747, 0xff5c8d89, 0xff9bc5be,
      0xfff4a6a0, 0xfffbf2e5, 0xff29335c, 0xffa8322d, 0xffe4572e,
      0xfff3a712, 0xfff5e9d6, 0xff12071f, 0xff3b1170, 0xff8b21b8,
      0xffe0218a, 0xff1b1b2f, 0xff4c3a87, 0xffb33f86, 0xfff5a65b, 0xfff2e8d5
  };

  // Text color for the India region label in the app header.
  public static final int REGION_LABEL_TEXT=0xff8de0d3;
  // Location dot when the last GPS reading is more than 30 seconds old.
  public static final int STALE_GPS_DOT=0xffe3ff00;
  // Location/stop marker colors, independent of the general UI palette.
  public static final int READY_GPS_DOT=0xff01ff1f, MOVING_GPS_DOT=0xffff4162,
      STOPPED_GPS_DOT=0xff004208, HISTORY_GPS_DOT=0xffff0080, SAVED_STOP_DOT=0xffff0080;
  // Border shared wby location and saved-stop dots (width in dp).
  public static final int DOT_OUTLINE_COLOR=0xffffffff;
  public static final int DOT_OUTLINE_WIDTH_DP=3;
  // Dark translucent border behind trail lines to keep them visible on maps.
  public static final int TRAIL_OUTLINE=0xff17deee;

  /** Empty follows the phone language. Set e.g. "en" or "hi" to pin UI copy.
   * Add translated keys below; missing translations fall back to ENGLISH.
   * Keep format placeholders (%s, %d, etc.) unchanged when editing translations.
   */
  public static final String UI_LANGUAGE="";
  public static final Map<String, Map<String,String>> TRANSLATIONS=Map.of();
  public static final Map<String,String> ENGLISH=Map.ofEntries(
      Map.entry("modules_title", "Extension modules"),
      Map.entry("module_road_title", "Road ahead"),
      Map.entry("module_journey_title", "Journey summary"),
      Map.entry("stop_leg_summary", "%.2f km since previous stop · avg %.1f km/h"),
      Map.entry("stop_distance_unavailable", "\nDistance since previous stop: unavailable"),
      Map.entry("gps_uncertain", "GPS uncertain"),
      Map.entry("0f_m_hr", "  ·  %.0f m/hr"),
      Map.entry("speed_unknown_m_hr", " · — m/hr"),
      Map.entry("speed_unknown", " · — km/h"),
      Map.entry("stop_signal_uncertain", "Weak GPS · stop retained"),
      Map.entry("0f_km_h", "  ·  %.0f km/h"),
      Map.entry("1f_km", "%.1f km"),
      Map.entry("6f_6f_1f_km_h_0f_m_s", "%.6f, %.6f\n%.1f km/h · ±%.0f m\n%s"),
      Map.entry("a_fresh_gps_fix_is_required_to_start", "A fresh GPS fix is required to start"),
      Map.entry("access_and_opening_hours_are_not_verified", "\nAccess and opening hours are not verified."),
      Map.entry("afterwards_sizes_are_estimates_until_the_server_reports_the_tota", " afterwards. Sizes are estimates until the server reports the total."),
      Map.entry("age_from_source_date", "Age from epoch: "),
      Map.entry("ago", " ago"),
      Map.entry("always_on_gps_every_5_seconds", "Always-on GPS · every 13 seconds"),
      Map.entry("resonations_title", "Resonations"),
      Map.entry("resonations_files", "Files"),
      Map.entry("resonations_play", "Play ↻"),
      Map.entry("resonations_pause", "Pause"),
      Map.entry("resonations_clear", "Clear"),
      Map.entry("resonations_empty", "Select audio to loop"),
      Map.entry("always_on_recording", "Always-on recording"),
      Map.entry("always_on_trail_recording", "Always-on trail recording"),
      Map.entry("always_recording_waiting_for_gps", "Always recording · waiting for GPS"),
      Map.entry("and_restarts_after_boot_update_when_background_location_is_allow", " and restarts after boot/update when background location is allowed."),
      Map.entry("app_settings", "App settings"),
      Map.entry("app_updates_daily_check_now", "App updates · daily / check now"),
      Map.entry("approximate_age", "Approximate age: "),
      Map.entry("arrived", "Arrived: "),
      Map.entry("automatic_writes_happen_every_15_minutes_drive_manages_network", " Automatic writes happen every 15 minutes; Drive manages network"),
      Map.entry("autostart_in_hyperos_and_set_battery_no_restrictions_force_stop", " autostart in HyperOS, and set Battery → No restrictions. Force-stop"),
      Map.entry("back_to_map", "← Back to map"),
      Map.entry("back_up_trail_now", "Back up trail now"),
      Map.entry("backup_failed", "Backup failed: "),
      Map.entry("backup_written_drive_handles_upload_sync", "Backup written · Drive handles upload/sync"),
      Map.entry("backups", " backups."),
      Map.entry("brouter_offline_driving", "BRouter · offline driving"),
      Map.entry("brouter_regional_tiles_india", "BRouter regional tiles · India"),
      Map.entry("cached", "Cached · "),
      Map.entry("cancel", "Cancel"),
      Map.entry("cancel_selected_tile_download", "Cancel selected tile download"),
      Map.entry("cancel_valhalla_download", "Cancel Valhalla download"),
      Map.entry("cannot_read_stop_list", "Cannot read stop list: "),
      Map.entry("cannot_retain_backup_access", "Cannot retain backup access: "),
      Map.entry("checking_cached_road_names", "Checking cached road names…"),
      Map.entry("checking_online_source", "Checking online source…"),
      Map.entry("checking_phone_storage", "Checking phone storage…"),
      Map.entry("choose_a_backup_location_first", "Choose a backup location first"),
      Map.entry("choose_a_destination_first", "Choose a destination first"),
      Map.entry("choose_a_destination_in_india", "Choose a destination in India"),
      Map.entry("choose_backup_location_google_drive_files", "Choose backup location · Google Drive / Files"),
      Map.entry("choose_brouter_or_valhalla_in_maps_downloads_to_calculate_offlin", "Choose BRouter or Valhalla in Maps & downloads to calculate offline routes."),
      Map.entry("choose_destination", "Choose destination"),
      Map.entry("choose_every_5_tile_along_your_journey_including_detours_tile_na", "Choose every 5° tile along your journey, including detours. Tile names show their southwest corner. Border tiles include adjacent roads. Size appears when the download server responds."),
      Map.entry("choose_location", "Choose location"),
      Map.entry("choose_your_backup_location", "Choose your backup location"),
      Map.entry("clear", "Clear"),
      Map.entry("close", "Close"),
      Map.entry("configure_valhalla_india_pack_source", "Configure Valhalla India pack source"),
      Map.entry("continuity_06x", "Continuity · #%06X"),
      Map.entry("contributors_bulk_offline_download_is_not_enabled", " contributors. Bulk offline download is not enabled."),
      Map.entry("current_gps_location", "Current GPS location"),
      Map.entry("dated_places_found_showing_up_to_50_nearest", " dated places found · showing up to 50 nearest"),
      Map.entry("departed", "\nDeparted: "),
      Map.entry("departed_2", "Departed: "),
      Map.entry("destination_5f_5f", "Destination %.5f, %.5f"),
      Map.entry("download", "Download"),
      Map.entry("download_the_india_pack_in_maps_downloads_first", "Download the India pack in Maps & downloads first"),
      Map.entry("download_update_selected_brouter_tile", "Download / update selected BRouter tile"),
      Map.entry("download_update_valhalla_india_pack", "Download / update Valhalla India pack"),
      Map.entry("drive_2f_km", " · drive %.2f km"),
      Map.entry("entire_collected_trail", "Entire collected trail"),
      Map.entry("esri_satellite", "Esri satellite"),
      Map.entry("esri_satellite_2", "Esri · satellite"),
      Map.entry("estimated", "Estimated "),
      Map.entry("export_all_gps_logs_geojson", "Export all GPS logs · GeoJSON"),
      Map.entry("exporting_gps_logs", "Exporting GPS logs…"),
      Map.entry("file_operation", "File operation"),
      Map.entry("find_refresh_nearby_places_osm", "Find / refresh nearby places (OSM)"),
      Map.entry("finding_dated_historic_places_within_25_km", "Finding dated historic places within 25 km…"),
      Map.entry("finding_gps_location", "Finding GPS location…"),
      Map.entry("fixes", " fixes · "),
      Map.entry("fixes_2", " fixes"),
      Map.entry("force_stop_prevents_automatic_restart_until_you_reopen_otrail", " force-stop prevents automatic restart until you reopen oTraiL."),
      Map.entry("from_start", " from start · "),
      Map.entry("full_trail", "Full trail · "),
      Map.entry("geojson_export_saved", "GeoJSON export saved"),
      Map.entry("google_maps", "Google Maps"),
      Map.entry("gps_6f_6f", "\nGPS: %.6f, %.6f"),
      Map.entry("gps_6f_6f_2", "GPS: %.6f, %.6f"),
      Map.entry("gps_gaps_present_recorded_distance_only", "\nGPS gaps present; recorded distance only"),
      Map.entry("gps_needs_a_clear_view_of_the_sky_stops_logged_after_2_min", "GPS needs a clear view of the sky · stops logged after 2 min"),
      Map.entry("gps_request_every_5_seconds_recording_starts_automatically_after", "GPS request: every 13 seconds, high accuracy. Recording starts automatically after permission is granted"),
      Map.entry("gps_request_interval_5_seconds_recording_runs_independently_of_l", "GPS request interval: 13 seconds, high accuracy. Recording runs independently of Live / History.\n\n"),
      Map.entry("gps_signal_stale", "GPS signal stale"),
      Map.entry("gps_stale_waiting_for_location", "GPS stale · waiting for location"),
      Map.entry("graph_import_failed", "Graph import failed: "),
      Map.entry("green_ready_blue_moving_turquoise_stopped_cyan_history", "● Green ready   ● Blue moving   ● Turquoise stopped   ● Cyan history"),
      Map.entry("h", "h "),
      Map.entry("hide_historic_labels", "Hide historic labels"),
      Map.entry("historic_place_search_is_already_running", "Historic-place search is already running"),
      Map.entry("historic_places", "Historic places"),
      Map.entry("historical_place_lookup_queued_failed_lookups_retry_after_24_hou", "Historical place lookup queued · failed lookups retry after 24 hours"),
      Map.entry("history", "History"),
      Map.entry("history_2", " history="),
      Map.entry("history_display", "History display"),
      Map.entry("history_unavailable", "History unavailable: "),
      Map.entry("hold_the_map_to_navigate_tap_stops_for_arrival_duration", "Hold the map to navigate · Tap stops for arrival & duration"),
      Map.entry("https_osrm_driving_endpoint", "HTTPS OSRM driving endpoint"),
      Map.entry("https_url_to_india_graph_tar", "HTTPS URL to India graph .tar"),
      Map.entry("identify_historical_stop_locations", "Identify historical stop locations"),
      Map.entry("import_an_india_map_file", "Import an India .map file"),
      Map.entry("import_retained_brouter_tile", "Import retained BRouter tile"),
      Map.entry("import_retained_valhalla_india_tar", "Import retained Valhalla India .tar"),
      Map.entry("importing_and_validating_india_map", "Importing and validating India map…"),
      Map.entry("in_app_driving_navigation", "In-app driving navigation"),
      Map.entry("in_the_save_picker_select_google_drive_open_the_folder_you_want_", "In the Save picker, select Google Drive, open the folder you want and save"),
      Map.entry("india_in", "INDIA  /  IN"),
      Map.entry("india_offline", "India offline"),
      Map.entry("india_pack_imported", "India pack imported"),
      Map.entry("installed_graph_available", " · installed graph available"),
      Map.entry("keep_recording_on_your_poco", "Keep recording on your POCO"),
      Map.entry("last_app_crash_view_copy", "Last app crash · view / copy"),
      Map.entry("last_fix", "last fix "),
      Map.entry("last_observation", "\nLast observation: "),
      Map.entry("last_observation_2", "Last observation: "),
      Map.entry("live", "Live"),
      Map.entry("live_trail", "LIVE TRAIL"),
      Map.entry("loading_journey_list", "Loading journey list…"),
      Map.entry("locate", "Locate"),
      Map.entry("locate_me_and_follow_gps", "Locate me and follow GPS"),
      Map.entry("locate_yourself_first_to_find_nearby_places", "Locate yourself first to find nearby places"),
      Map.entry("location_battery_settings", "Location & battery settings"),
      Map.entry("location_not_yet_identified", "Location not yet identified"),
      Map.entry("location_permissions", "Location permissions"),
      Map.entry("looking_up_road_cache_first", "Looking up road · cache first…"),
      Map.entry("lookup_unavailable_cached_places_kept", "Lookup unavailable; cached places kept. "),
      Map.entry("m", " m · "),
      Map.entry("m_2", " m"),
      Map.entry("m_3", "m "),
      Map.entry("m_away_mapped_alongside", " m away\nMapped alongside "),
      Map.entry("m_openstreetmap_contributors", " m · © OpenStreetMap contributors"),
      Map.entry("manual_update_history", "Manual update history"),
      Map.entry("map_corrections", "Map corrections"),
      Map.entry("map_data_downloads_straight_to_phone_storage_wi_fi_or_cellular_c", "Map data downloads straight to phone storage. Wi-Fi or cellular. Completed datasets"),
      Map.entry("map_destination", "Map destination"),
      Map.entry("map_unavailable", "Map unavailable: "),
      Map.entry("maps_downloads_india_only", "MAPS & DOWNLOADS  ·  India only"),
      Map.entry("min_no_live_traffic", " min · no live traffic"),
      Map.entry("navigate", "Navigate"),
      Map.entry("navigate_to_5f_5f", "Navigate to %.5f, %.5f"),
      Map.entry("navigation_destination", "Navigation destination"),
      Map.entry("needs_a_prepared_india_graph_pack_uncompressed_tar_containing_gp", "Needs a prepared India graph pack: uncompressed .tar containing .gph tiles, compatible with Valhalla 3.9.0. Your Mapsforge .map and raw OSM .pbf are not routing graphs. Configure a graph-pack URL or import a retained pack. No India download source is preconfigured."),
      Map.entry("new_file", " new file."),
      Map.entry("no_mapped_roadside_landmarks_cached_here_coverage_depends_on_ope", "No mapped roadside landmarks cached here. Coverage depends on OpenStreetMap."),
      Map.entry("no_matching_place_in_india_try_a_nearby_town_or_coordinates", "No matching place in India. Try a nearby town or coordinates."),
      Map.entry("observed_duration", "\nObserved duration: "),
      Map.entry("offline", "Offline "),
      Map.entry("offline_connect_to_fetch_this_area", "Offline · connect to fetch this area"),
      Map.entry("offline_enter_latitude_longitude_or_hold_a_point_on_the_map", "Offline: enter latitude, longitude or hold a point on the map"),
      Map.entry("offline_map_india", "OFFLINE MAP  /  INDIA"),
      Map.entry("offline_only_showing_cached_places_turn_it_off_to_refresh", "Offline only: showing cached places. Turn it off to refresh."),
      Map.entry("old_places_200_600_years_25_km", "Old places · 200–600 years · 25 km"),
      Map.entry("on_poco_enable_background_autostart_and_set_battery_no_restricti", "On POCO: enable Background autostart and set Battery → No restrictions. Android"),
      Map.entry("on_the_move", "On the move"),
      Map.entry("on_this_phone_enable_drive_in_the_picker_s_side_menu_if_availabl", " on this phone. Enable Drive in the picker's side menu if available."),
      Map.entry("online_only", "ONLINE ONLY"),
      Map.entry("online_routing_server", "Online routing server"),
      Map.entry("online_update_unavailable_any_cached_name_above_is_retained", "\nOnline update unavailable; any cached name above is retained."),
      Map.entry("online_viewed_tile_cache", "ONLINE + VIEWED-TILE CACHE"),
      Map.entry("openstreetmap_contributors", "\n© OpenStreetMap contributors"),
      Map.entry("openstreetmap_roads", "OpenStreetMap · roads"),
      Map.entry("optional_source_data_india", "OPTIONAL SOURCE DATA  /  INDIA"),
      Map.entry("osm_roads", "OSM roads"),
      Map.entry("osrm_fossgis_openstreetmap_contributors", "OSRM / FOSSGIS · © OpenStreetMap contributors"),
      Map.entry("osrm_online_driving", "OSRM · online driving"),
      Map.entry("other_apps", "Other apps"),
      Map.entry("other_maps", "Other maps"),
      Map.entry("otrail", "oTraiL"),
      Map.entry("otrail_backup_json_you_can_also_use_a_persistent_documents_folde", " otrail-backup.json. You can also use a persistent Documents folder"),
      Map.entry("otrail_osrm_fossgis_provides_online_routes_rerouting_needs_inter", " oTraiL. OSRM / FOSSGIS provides online routes; rerouting needs internet. No live"),
      Map.entry("pass_02d_06x", "Pass %02d · #%06X"),
      Map.entry("persistent_backup", "PERSISTENT BACKUP"),
      Map.entry("phone_storage", " · phone storage"),
      Map.entry("place_in_india_or_latitude_longitude", "Place in India or latitude, longitude"),
      Map.entry("place_lookup", "\nPlace lookup: "),
      Map.entry("places", "Places"),
      Map.entry("precise_location_is_required_for_automatic_gps_recording", "Precise location is required for automatic GPS recording"),
      Map.entry("preview_in_otrail", "Preview in oTraiL"),
      Map.entry("queued_on_phone_wi_fi_or_cellular", "Queued on phone · Wi-Fi or cellular"),
      Map.entry("ready_when_you_are", "Ready when you are"),
      Map.entry("reboot_location_permission_is_ready", "Reboot location permission is ready."),
      Map.entry("reboot_permission_ready", "REBOOT PERMISSION READY"),
      Map.entry("reboot_recovery_allow_location_all_the_time_enable_background", " reboot recovery, allow location all the time, enable Background"),
      Map.entry("reboot_setup_needed", "REBOOT SETUP NEEDED"),
      Map.entry("recenter", "Recenter"),
      Map.entry("recorded_stops", "Recorded stops · "),
      Map.entry("recording", "Recording"),
      Map.entry("recording_can_restart_after_reboot", " recording can restart after reboot."),
      Map.entry("recording_starts_automatically_with_precise_location_permission_", "Recording starts automatically with precise location permission. For"),
      Map.entry("remaining", " remaining · "),
      Map.entry("repeated_road_passes_31_colors", "Repeated road passes · 31 colors"),
      Map.entry("requires_reopening_the_app", " requires reopening the app."),
      Map.entry("restore_failed", "Restore failed: "),
      Map.entry("restore_trail_from_google_drive_files", "Restore trail from Google Drive / Files"),
      Map.entry("restoring_trail_snapshot", "Restoring trail snapshot…"),
      Map.entry("retry_download", "Retry download"),
      Map.entry("road_at_this_point", "Road at this point"),
      Map.entry("road_basemap_with_the_same_trails_and_stop_details_openstreetmap", "Road basemap with the same trails and stop details. © OpenStreetMap contributors. Public"),
      Map.entry("road_cache_unavailable_tap_road_to_retry", "Road cache unavailable · tap road to retry"),
      Map.entry("road_landmarks_cache_first", "Road landmarks · cache first"),
      Map.entry("road_landmarks_shown_while_on_the_move", "Road landmarks · shown while on the move"),
      Map.entry("roadside_landmarks_ahead", " roadside landmarks ahead · "),
      Map.entry("routing_choose_your_engine", "Routing · choose your engine"),
      Map.entry("routing_endpoint_saved", "Routing endpoint saved"),
      Map.entry("routing_graph_ready_offline", "Routing graph ready offline"),
      Map.entry("satellite_context_with_your_trail_overlay_tiles_esri_maxar_earth", "Satellite context with your trail overlay. Tiles © Esri, Maxar, Earthstar Geographics and"),
      Map.entry("save", "Save"),
      Map.entry("saving_trail_backup", "Saving trail backup…"),
      Map.entry("search", "Search"),
      Map.entry("search_is_already_running", "Search is already running"),
      Map.entry("search_unavailable", "Search unavailable: "),
      Map.entry("searching_places", "Searching places…"),
      Map.entry("select_the_backup_target_again_a_fresh_backup_target_should_be_a", " select the backup target again. A fresh backup target should be a"),
      Map.entry("settings", "Settings"),
      Map.entry("setup_needed_grant_location_access", "Setup needed · grant location access"),
      Map.entry("setup_needed_grant_precise_location_and_select_allow_all_the_tim", "Setup needed: grant precise location and select Allow all the time so"),
      Map.entry("sha_256_from_pack_publisher_optional", "SHA-256 from pack publisher (optional)"),
      Map.entry("show_cached_nearby_places", "Show cached nearby places"),
      Map.entry("since_previous_stop_2f_km_average_since_departure_1f_km_h_s", "\nSince previous stop: %.2f km\nAverage since departure: %.1f km/h%s"),
      Map.entry("size_pending", " · size pending"),
      Map.entry("size_pending_download", "Size pending download"),
      Map.entry("source_date", "Source date: "),
      Map.entry("source_dated_places_cached_within_25_km", " source-dated places cached within 25 km"),
      Map.entry("start", "Start"),
      Map.entry("stay_in_downloads_otrail_across_reinstall_import_the_retained_ma", " stay in Downloads/oTraiL across reinstall; import the retained .map file"),
      Map.entry("stop", "Stop #"),
      Map.entry("stop_list", "Stop list"),
      Map.entry("stopped", "Stopped · "),
      Map.entry("stops", "Stops"),
      Map.entry("stops_2", " stops · ±"),
      Map.entry("sync_after_reinstall_use_restore_to_reopen_the_saved_backup_then", " sync. After reinstall, use Restore to reopen the saved backup, then"),
      Map.entry("take_india_with_you", "Take India with you."),
      Map.entry("tap_a_road_for_its_cached_name", "Tap a road for its cached name"),
      Map.entry("tap_a_road_on_the_map_to_identify_it", "Tap a road on the map to identify it"),
      Map.entry("text_directions_no_voice", "TEXT DIRECTIONS · NO VOICE"),
      Map.entry("this_navigation_app_is_not_installed", "This navigation app is not installed"),
      Map.entry("tiles_do_not_permit_bulk_offline_pack_downloads", " tiles do not permit bulk/offline pack downloads."),
      Map.entry("tiles_esri_maxar_earthstar_geographics_and_contributors", "Tiles © Esri · Maxar · Earthstar Geographics and contributors"),
      Map.entry("total_2f_km", "Total %.2f km · "),
      Map.entry("tracker", "Tracker"),
      Map.entry("tracker_permissions_reboot_setup", "Tracker permissions & reboot setup"),
      Map.entry("traffic_or_voice_other_apps_remain_optional", " traffic or voice. Other apps remain optional."),
      Map.entry("trail", "Trail #"),
      Map.entry("trail_2f_km", "Trail %.2f km · "),
      Map.entry("trail_backup_google_drive_or_phone_folder", "Trail backup · Google Drive or phone folder"),
      Map.entry("trail_display_could_not_finish_your_recorded_data_is_safe", "Trail display could not finish; your recorded data is safe"),
      Map.entry("trail_history_restored_re_select_the_backup_target_to_resume_aut", "Trail history restored. Re-select the backup target to resume automatic"),
      Map.entry("trail_palette_31_pass_colors", "Trail palette · 31 pass colors"),
      Map.entry("turns", "Turns"),
      Map.entry("unavailable_retry_after_24h", "unavailable; retry after 24h"),
      Map.entry("update_log", "Update log"),
      Map.entry("update_manually", "Update manually"),
      Map.entry("update_unavailable_cache_kept", " · update unavailable; cache kept"),
      Map.entry("updated", "Updated · "),
      Map.entry("use_navigate_to_search_or_hold_a_point_on_the_map_preview_and_st", "Use Navigate to search or hold a point on the map. Preview and start a driving route in"),
      Map.entry("valhalla_graph_download", "Valhalla graph download"),
      Map.entry("valhalla_india_routing_graph", "Valhalla · India routing graph"),
      Map.entry("valhalla_offline_driving", "Valhalla · offline driving"),
      Map.entry("validating_routing_graph", "Validating routing graph…"),
      Map.entry("waiting_for_a_fresh_gps_fix", "Waiting for a fresh GPS fix"),
      Map.entry("waiting_for_an_accurate_gps_fix", "Waiting for an accurate GPS fix…"),
      Map.entry("waiting_for_gps", " · waiting for GPS"),
      Map.entry("where_are_you_going", "Where are you going?"),
      Map.entry("years_openstreetmap_contributors_not_independently_verified_cach", " years\n© OpenStreetMap contributors · not independently verified\nCached: "),
      Map.entry("your_journeys", "Your journeys"),
      Map.entry("your_map_and_recorded_trail_stay_the_same_download_routing_graph", "Your map and recorded trail stay the same. Download routing graphs here; no graphs are bundled in the app. Offline engines calculate new routes and reroute without internet. No voice guidance."),
      Map.entry("your_road_your_record_personal_navigation", "YOUR ROAD. YOUR RECORD.  ·  Personal navigation"));
  public static String text(String key) {
    String language=UI_LANGUAGE.isEmpty()?Locale.getDefault().getLanguage():UI_LANGUAGE;
    return TRANSLATIONS.getOrDefault(language,Map.of()).getOrDefault(key,ENGLISH.getOrDefault(key,key));
  }
  // Map toolbar: CAM shares Locate's visual scale and 56dp touch target.
  public static final int CAM_LOGO_COLOR=LOCATE_LOGO_COLOR;
  public static final int CAM_BACKGROUND=LOCATE_BACKGROUND;
  public static final int CAM_BORDER_COLOR=LOCATE_BORDER_COLOR;
  public static final int MAP_ICON_GAP_DP=6;
  // WTF! base-app camera. Travel vector: x=-0.8 left, y=1 up.
  public static final String WTF_LABEL="WTF! mode", WTF_ICON_TEXT="!";
  public static final int WTF_ICON_SIZE_SP=24;
  public static final float WTF_DIAGONAL_X=-.8f, WTF_DIAGONAL_Y=1f;
  public static final float WTF_MAX_ACCURACY_M=35, WTF_MIN_SPEED_MPS=1.5f;
  public static final int WTF_TURN_ANIMATION_MS=650;
  public static final int WTF_ENABLED_TEXT=0xff00ffff, WTF_DISABLED_TEXT=0xffa7a7ac;
  public static final int WTF_ENABLED_BACKGROUND=0xff8b21b8, WTF_DISABLED_BACKGROUND=0xff000000;
  public static final int WTF_NORTH_COLOR=0xff40e0d0, WTF_NORTH_BACKGROUND=0xcc000000;
  public static final String NAV_TITLE_FONT="sans-serif", LIVE_BUBBLE_FONT="sans-serif-medium";
  public static final int LIVE_BUBBLE_BACKGROUND=0xee000000, LIVE_BUBBLE_TEXT=0xff00ffff;
  public static final int LIVE_BUBBLE_WIDTH_DP=154, LIVE_WORSHIP_RADIUS_M=25000;
  public static final String[] LIVE_WORSHIP_SERVERS={"https://overpass.private.coffee/api/interpreter","https://overpass-api.de/api/interpreter"};
  // 0.8 of a 16dp base corner, shared by navigation and GPS panels.
  public static final float NAV_BOX_CORNER_DP=12.8f;
  public static final int LIVE_BUBBLE_SIZE_SP=12, GPS_SMILE_COLOR=0xffffffff;
  public static final float GPS_SMILE_MAX_ACCURACY_M=15;
  public static final int LIVE_BUBBLE_BORDER_DP=4;
}
