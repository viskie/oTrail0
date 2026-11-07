package in.otrail;

import java.util.List;

/** Stable IDs also name persisted enable flags; never reuse an ID for a different module. */
final class ModuleCatalog {
  record Entry(String id,String titleKey,String summaryKey,boolean available) {}
  static final List<Entry> ENTRIES=List.of(
      new Entry("road-ahead","module_road_title","module_road_summary",false),
      new Entry("journey-summary","module_journey_title","module_journey_summary",false));
  static TrailModule create(String id){
    throw new IllegalArgumentException("Module is not bundled: "+id);
  }
}
