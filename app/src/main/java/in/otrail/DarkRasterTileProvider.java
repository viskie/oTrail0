package in.otrail;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.tilesource.ITileSource;

/** Applies the configured night treatment to raster map tiles, before app overlays are drawn. */
final class DarkRasterTileProvider extends MapTileProviderBasic {
  private final ColorMatrixColorFilter filter;

  DarkRasterTileProvider(Context context, ITileSource source) {
    super(context, source);
    float range=UiConfig.MAP_DARK_RASTER_MAX_LUMA-UiConfig.MAP_DARK_RASTER_MIN_LUMA;
    float scale=range/255f;
    float red=-.213f*scale,green=-.715f*scale,blue=-.072f*scale;
    float bright=UiConfig.MAP_DARK_RASTER_MAX_LUMA;
    ColorMatrix matrix=new ColorMatrix(new float[]{
        red,green,blue,0,bright,
        red,green,blue,0,bright,
        red,green,blue,0,bright,
        0,0,0,1,0
    });
    filter=new ColorMatrixColorFilter(matrix);
  }

  @Override
  @SuppressWarnings("deprecation")
  public Drawable getMapTile(long index) {
    Drawable tile=super.getMapTile(index);
    if(tile!=null)tile.setColorFilter(filter);
    return tile;
  }
}
