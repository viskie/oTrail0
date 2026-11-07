package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashSet;
public class ModuleCatalogTest {
 @Test public void stableIdsUniqueAndPlansNotAvailable(){
   HashSet<String> ids=new HashSet<>();
   for(ModuleCatalog.Entry e:ModuleCatalog.ENTRIES){assertTrue(ids.add(e.id()));assertFalse(e.id().isBlank());
     assertFalse(UiConfig.text(e.titleKey()).isBlank());
     assertFalse(e.available());}
   assertEquals(2,ids.size());
 }
 @Test(expected=IllegalArgumentException.class) public void plannedModuleCannotLoad(){ModuleCatalog.create("road-ahead");}
}
