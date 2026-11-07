package in.otrail.modules;
import java.io.File;
public interface PublisherV1 {
  void run(String action,File data,String configuration,ModuleV1.Progress progress) throws Exception;
}
