#!/usr/bin/env python3
"""Compile real India OSM POIs into an independent read-only Android asset.
Host dependencies: osmium, shapely. Raw snapshots stay outside the repository.
"""
import argparse,collections,datetime,hashlib,json,math,pathlib,re,sqlite3,unicodedata,zipfile
import osmium
from shapely.geometry import shape,Point,Polygon
from shapely.strtree import STRtree
from shapely import wkb

def digest(path):
 h=hashlib.sha256()
 with open(path,'rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def normalized(value):return unicodedata.normalize('NFKC',value).lower().strip()
def build(args):
 out=pathlib.Path(args.output);out.parent.mkdir(parents=True,exist_ok=True)
 if out.exists():out.unlink()
 db=sqlite3.connect(out);db.execute('PRAGMA journal_mode=OFF');db.execute('PRAGMA synchronous=OFF')
 db.execute('CREATE TABLE places(id TEXT PRIMARY KEY,name TEXT NOT NULL,religion TEXT NOT NULL,state TEXT NOT NULL,district TEXT NOT NULL,city TEXT NOT NULL,area TEXT NOT NULL,pin TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,start_date TEXT NOT NULL,source TEXT NOT NULL,score INTEGER NOT NULL,year INTEGER NOT NULL,search TEXT NOT NULL)')
 with zipfile.ZipFile(args.hot) as z:hot=json.loads(z.read('cultural_places.geojson'))['features']
 hints={f['properties']['id']:f for f in hot};del hot
 boundaries=json.loads(pathlib.Path(args.states).read_text())['features'];geoms=[shape(f['geometry']) for f in boundaries];tree=STRtree(geoms)
 names=[f['properties'].get('shapeName','') for f in boundaries]
 factory=osmium.geom.WKBFactory();count=0;skips=collections.Counter();seen=collections.defaultdict(list)
 filt=osmium.filter.TagFilter(('amenity','place_of_worship'),('amenity','police'),('historic','temple'))
 processor=osmium.FileProcessor(args.pbf).with_locations('sparse_file_array,'+str(pathlib.Path(args.pbf).with_suffix('.locations.idx'))).with_filter(filt)
 for obj in processor:
  tags=dict(obj.tags);kind='node' if obj.is_node() else 'way' if obj.is_way() else 'relation';key=f'{kind}/{obj.id}'
  if tags.get('addr:country','IN').upper() not in {'IN','IND','INDIA','BHARAT','भारत'}:skips['other_country']+=1;continue
  hint=hints.get(key);point=None
  try:
   if obj.is_node():point=Point(obj.location.lon,obj.location.lat)
   elif obj.is_way():
    line=wkb.loads(factory.create_linestring(obj),hex=True)
    point=Polygon(line.coords).representative_point() if line.is_ring else line.interpolate(.5,normalized=True)
   elif hint:point=shape(hint['geometry']).representative_point()
  except Exception:
   if hint:point=shape(hint['geometry']).representative_point()
  if point is None or not(6<=point.y<=38 and 67<=point.x<=99):skips['no_india_coordinate']+=1;continue
  religion='police' if tags.get('amenity')=='police' else tags.get('religion','unknown').strip().lower() or 'unknown'
  name=tags.get('name:en') or tags.get('name') or tags.get('name:hi') or ''
  prop=hint['properties'] if hint else {};state=prop.get('adm1_name') or ''
  if not state:
   hit=tree.query(point,predicate='intersects')
   if len(hit):state=names[int(hit[0])]
  if not state:state=tags.get('addr:state') or ''
  district=tags.get('addr:district') or prop.get('adm2_name') or ''
  city=tags.get('addr:city') or tags.get('addr:town') or tags.get('addr:village') or tags.get('is_in:city') or ''
  area=tags.get('addr:suburb') or prop.get('adm3_name') or ''
  pin=tags.get('addr:postcode') or tags.get('postal_code') or '';pin=pin.strip() if re.fullmatch('[1-9][0-9]{5}',pin.strip()) else ''
  date=tags.get('start_date') or tags.get('construction:start_date') or '';match=re.search(r'(?<!\d)([12]\d{3}|[1-9]\d{2})(?!\d)',date);year=int(match.group(1)) if match else 9999
  score=(100 if tags.get('wikidata') or tags.get('wikipedia') else 0)+(50 if tags.get('historic') else 0)+(40 if tags.get('heritage') else 0)+(10 if name else 0)+(max(0,min(30,(2026-year)//50)) if year<9999 else 0)
  # Merge only a clear same-name/same-religion match within 25m; never infer faith from names.
  norm=normalized(name);cell=(religion,int(point.x/.0003),int(point.y/.0003));duplicate=False
  if norm:
   for x in (-1,0,1):
    for y in (-1,0,1):
     for old in seen.get((religion,cell[1]+x,cell[2]+y),[]):
      distance=math.hypot((point.x-old[1])*111000*math.cos(math.radians(point.y)),(point.y-old[2])*111000)
      if old[0]==norm and distance<=25:duplicate=True
  if duplicate:skips['near_identical_name']+=1;continue
  if norm:seen[cell].append((norm,point.x,point.y))
  source='https://www.openstreetmap.org/'+key
  search=normalized(' '.join([name,state,district,city,area,pin,tags.get('name','')]))
  db.execute('INSERT OR IGNORE INTO places VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',(key,name,religion,state,district,city,area,pin,round(point.y,7),round(point.x,7),date,source,score,year,search));count+=1
  if count%10000==0:db.commit();print('Compiled',count,'POIs',flush=True)
 db.execute('CREATE INDEX place_geo ON places(lat,lon)');db.execute('CREATE INDEX place_religion_geo ON places(religion,lat,lon)');db.execute('CREATE INDEX place_filters ON places(state,religion,pin)');db.execute('CREATE INDEX place_pin ON places(pin)');db.execute('CREATE INDEX place_rank ON places(score DESC,year,name)');db.commit();assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
 counts=dict(db.execute('SELECT religion,count(*) FROM places GROUP BY religion'));missing=dict(db.execute("SELECT 'missing_pin',count(*) FROM places WHERE pin='' UNION ALL SELECT 'missing_city',count(*) FROM places WHERE city='' UNION ALL SELECT 'missing_state',count(*) FROM places WHERE state='' UNION ALL SELECT 'dated',count(*) FROM places WHERE start_date<>'' UNION ALL SELECT 'named',count(*) FROM places WHERE name<>'' AND religion<>'police' UNION ALL SELECT 'unnamed',count(*) FROM places WHERE name='' AND religion<>'police'"));db.close()
 info={'schema':1,'built':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sha256':digest(out),'counts':counts,'coverage':missing,'skipped':dict(skips),'requested':{'hindu':55000,'muslim':5000,'christian':5000,'buddhist':500},'source':'OpenStreetMap contributors; Geofabrik India extract; HOT India cultural-place administrative fields; geoBoundaries India ADM1','license':'ODbL 1.0; geoBoundaries attribution CC BY 2.5 IN','snapshot':'2026-11-09','source_urls':['https://download.geofabrik.de/asia/india-261109.osm.pbf','https://data.humdata.org/dataset/hotosm_ind_cultural_places','https://www.geoboundaries.org/api/current/gbOpen/IND/ADM1/'],'source_files':{pathlib.Path(p).name:digest(p) for p in [args.pbf,args.hot,args.states]},'notes':'Sourced records, not a census or certified prominence ranking. Religion and PIN are never guessed. Source date is not independently verified age. Unknown dates/PINs remain empty.'}
 out.with_name('religious-directory.json').write_text(json.dumps(info,indent=2,ensure_ascii=False)+'\n');print(json.dumps(info,ensure_ascii=False,indent=2),flush=True)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--pbf',required=True);p.add_argument('--hot',required=True);p.add_argument('--states',required=True);p.add_argument('--output',default='app/src/main/assets/religious-directory.sqlite');build(p.parse_args())
