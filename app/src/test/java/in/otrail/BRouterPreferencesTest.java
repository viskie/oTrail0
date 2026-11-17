package in.otrail;
import org.junit.Test;
import btools.expressions.*;
import java.nio.file.*;
public class BRouterPreferencesTest {
 @Test public void eachSoftPreferenceIsAcceptedByEmbeddedProfileParser()throws Exception {
  Path assets=Paths.get("src/main/assets/brouter");if(!Files.exists(assets))assets=Paths.get("app/src/main/assets/brouter");
  String original=Files.readString(assets.resolve("car-vario.brf"));
  for(String key:RoutePreferences.KEYS){
   Path temp=Files.createTempFile("otrail-profile-",".brf");
   try {
    Files.writeString(temp,RoutePreferences.brouterProfile(original,key));
    BExpressionMetaData meta=new BExpressionMetaData();BExpressionContextWay way=new BExpressionContextWay(1024,meta);BExpressionContextNode node=new BExpressionContextNode(0,meta);node.setForeignContext(way);
    meta.readMetaData(assets.resolve("lookups.dat").toFile());way.parseFile(temp.toFile(),"global");node.parseFile(temp.toFile(),"global");
   }finally{Files.deleteIfExists(temp);}
  }
 }
}
