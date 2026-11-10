package in.otrail;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Reusable recessed neon border. Use one drawable instance per view. */
public final class NeonBorderDrawable extends Drawable {
  public enum Palette { CURRENT, LIME, BRIGHT_ORANGE, BRIGHT_YELLOW }
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rim=new RectF(),inner=new RectF();
  private final float density,inset,corner;
  private final boolean pill;
  private final int color,reflection;
  private final Palette palette;
  private Shader interior,glow,topHighlight;
  private int alpha=255;private ColorFilter filter;

  private NeonBorderDrawable(View view,Palette palette,boolean pill,float cornerDp){
    this.palette=palette;this.pill=pill;density=view.getResources().getDisplayMetrics().density;
    inset=6f*density;corner=cornerDp*density;
    color=switch(palette){case CURRENT->UiConfig.NEON_CURRENT;case LIME->UiConfig.NEON_LIME;case BRIGHT_ORANGE->UiConfig.NEON_BRIGHT_ORANGE;case BRIGHT_YELLOW->UiConfig.NEON_BRIGHT_YELLOW;};
    reflection=palette==Palette.CURRENT?UiConfig.NEON_CURRENT_REFLECTION:withAlpha(color,153);
  }
  public static NeonBorderDrawable button(View view){return button(view,Palette.CURRENT);}
  public static NeonBorderDrawable button(View view,Palette palette){return new NeonBorderDrawable(view,palette,true,0);}
  public static NeonBorderDrawable panel(View view,Palette palette,float cornerDp){return new NeonBorderDrawable(view,palette,false,cornerDp);}
  /** Shared shape-following halo; call before the icon fill/rim, never on ripple masks. */
  public static void pathGlow(Canvas canvas,Path path,Paint source,int color,float rimWidth){
    Paint p=new Paint(source);p.setStyle(Paint.Style.STROKE);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeCap(Paint.Cap.ROUND);
    RectF box=new RectF();path.computeBounds(box,true);p.setShader(new LinearGradient(0,box.top,0,box.bottom,new int[]{withAlpha(color,100),color},null,Shader.TileMode.CLAMP));p.setColor(Color.WHITE);
    p.setStrokeWidth(rimWidth*3);p.setAlpha(45);canvas.drawPath(path,p);
    p.setStrokeWidth(rimWidth*1.5f);p.setAlpha(100);canvas.drawPath(path,p);
    p.setStrokeWidth(rimWidth*1);p.setAlpha(255);canvas.drawPath(path,p);
  }
  public static Drawable menuIcon(View view,int color){return new MenuIcon(view.getResources().getDisplayMetrics().density,color);}
  private static final class MenuIcon extends Drawable {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final float density;private final int color;
    MenuIcon(float density,int color){this.density=density;this.color=color;}
    @Override public void draw(Canvas canvas){RectF box=new RectF(getBounds());box.inset(box.width()*(1-UiConfig.LOCATE_VISUAL_SCALE)/2,box.height()*(1-UiConfig.LOCATE_VISUAL_SCALE)/2);Path path=new Path();path.addRoundRect(box,10*density,10*density,Path.Direction.CW);pathGlow(canvas,path,p,color,density);p.setStyle(Paint.Style.FILL);p.setColor(UiConfig.LOCATE_BACKGROUND);canvas.drawPath(path,p);p.setStyle(Paint.Style.STROKE);p.setColor(color);p.setStrokeWidth(density);canvas.drawPath(path,p);}
    @Override public void setAlpha(int alpha){p.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
  private static int withAlpha(int color,int alpha){return(color&0xffffff)|(alpha<<24);}
  private static int lighter(int color){return Color.rgb((Color.red(color)*3+255)/4,(Color.green(color)*3+255)/4,(Color.blue(color)*3+255)/4);}
  @Override protected void onBoundsChange(Rect bounds){
    rim.set(bounds);rim.inset(inset,inset);inner.set(rim);inner.inset(density,density);
    if(rim.width()<=0||rim.height()<=0)return;
    interior=new LinearGradient(0,rim.top,0,rim.bottom,UiConfig.NEON_INTERIOR,null,Shader.TileMode.CLAMP);
    int[] colors=palette==Palette.CURRENT?UiConfig.NEON_CURRENT_STOPS:new int[]{withAlpha(color,51),withAlpha(color,119),color,withAlpha(color,255)};
    glow=new LinearGradient(0,rim.top,0,rim.bottom,colors,new float[]{0,.35f,.78f,1},Shader.TileMode.CLAMP);
    int bright=lighter(color);topHighlight=new LinearGradient(inner.left,0,inner.right,0,new int[]{withAlpha(bright,100),bright,bright,withAlpha(bright,100)},new float[]{0,.18f,.82f,1},Shader.TileMode.CLAMP);
  }
  private void stroke(Canvas canvas,RectF bounds,float radius,float width,int opacity,Shader shader,int tint){
    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width*density);paint.setShader(shader);paint.setColor(tint);paint.setAlpha(opacity*alpha/255);paint.setColorFilter(filter);canvas.drawRoundRect(bounds,radius,radius,paint);
  }
  @Override public void draw(Canvas canvas){
    if(rim.width()<=0||rim.height()<=0)return;
    float radius=pill?rim.height()/2f:Math.min(corner,Math.min(rim.width(),rim.height())/2f);
    paint.setStyle(Paint.Style.FILL);paint.setShader(interior);paint.setColor(Color.WHITE);paint.setAlpha(alpha);paint.setColorFilter(filter);canvas.drawRoundRect(rim,radius,radius,paint);
    stroke(canvas,rim,radius,12,35,glow,Color.WHITE);
    stroke(canvas,rim,radius,7,95,glow,Color.WHITE);
    stroke(canvas,rim,radius,2.5f,255,glow,Color.WHITE);
    float innerRadius=Math.max(0,radius-density);
    int save=canvas.save();canvas.clipRect(rim.left,rim.top+rim.height()*.55f,rim.right,rim.bottom);
    stroke(canvas,inner,innerRadius,.7f,153,null,reflection);canvas.restoreToCount(save);
    // A separate bright inner top lip stays visible against the black recess.
    save=canvas.save();canvas.clipRect(rim.left,rim.top-density,rim.right,rim.top+Math.min(rim.height()*.45f,radius+2*density));
    stroke(canvas,inner,innerRadius,UiConfig.NEON_INNER_TOP_WIDTH_DP,UiConfig.NEON_INNER_TOP_ALPHA,topHighlight,Color.WHITE);canvas.restoreToCount(save);
  }
  @Override public void setAlpha(int value){alpha=Math.max(0,Math.min(255,value));invalidateSelf();}
  @Override public void setColorFilter(ColorFilter value){filter=value;invalidateSelf();}
  @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
