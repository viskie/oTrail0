package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashSet;
public class ModuleCatalogTest {
 @Test public void stableIdsUniqueAndPlansNotAvailable(){
   HashSet<String> ids=new HashSet<>();
   for(ModuleCatalog.Entry e:ModuleCatalog.ENTRIES){assertTrue(ids.add(e.id()));assertFalse(e.id().isBlank());
     assertFalse(UiConfig.text(e.titleKey()).isBlank());
     if(!e.id().equals("gps-details"))assertFalse(e.available());}
   assertTrue(ModuleCatalog.ENTRIES.get(0).available());
 }
 @Test(expected=IllegalArgumentException.class) public void plannedModuleCannotLoad(){ModuleCatalog.create("road-ahead");}
}
