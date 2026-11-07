package in.otrail.modules;
import java.io.File;
/** Compiler libraries are downloaded separately from both the APK and editor module. */
public interface CompilerV1 {
  void compile(File workspace,File tools,File data,String configuration,ModuleV1.Progress progress) throws Exception;
}
