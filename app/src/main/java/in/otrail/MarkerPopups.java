package in.otrail;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.infowindow.InfoWindow;

/** Always close the previous popup before rebinding the next marker's content. */
final class MarkerPopups {
  private MarkerPopups() {}

  static void openOnly(MapView map, Marker marker) {
    InfoWindow.closeAllInfoWindowsOn(map);
    marker.showInfoWindow();
  }
}
