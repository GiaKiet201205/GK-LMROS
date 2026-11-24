"""Offline OSM address catalogue. Never invent house numbers or jitter coordinates."""
from collections import defaultdict
import json
import math
from pathlib import Path

CATALOG = Path(__file__).resolve().parents[2] / 'infra/database/demo/hcm-addresses.json'


def load_catalog():
    catalog = json.loads(CATALOG.read_text())
    points = catalog['locations']
    if len(points) < 300 or len({p['street'] for p in points}) < 100:
        raise ValueError('City catalogue has insufficient street/address coverage')
    for p in points:
        if not (10.66 <= p['lat'] <= 10.91 and 106.53 <= p['lng'] <= 106.86):
            raise ValueError('Address outside the urban HCMC catalogue bounds')
        if not p['housenumber'] or not p['street'] or not p['source_url'].startswith('https://www.openstreetmap.org/'):
            raise ValueError('Address missing house number, street or source provenance')
    return catalog


def distance_km(a, b):
    lat1, lat2 = math.radians(a['lat']), math.radians(b['lat'])
    dlat, dlng = lat2-lat1, math.radians(b['lng']-a['lng'])
    h = math.sin(dlat/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin(dlng/2)**2
    return 12742 * math.asin(math.sqrt(min(1, h)))


def historical_tour(rows, phase, origin):
    """Plausible past routes only. Live orders are left to the application optimizer."""
    def point(row):
        return {'lat':row['pickup_lat'] if phase=='pickup' else row['lat'],
                'lng':row['pickup_lng'] if phase=='pickup' else row['lng']}
    remaining=list(rows);result=[];current=origin
    while remaining:
        row=min(remaining,key=lambda r:distance_km(current,point(r)))
        remaining.remove(row);result.append(row);current=point(row)
    def total(tour):
        nodes=[origin]+[point(r) for r in tour]+[origin]
        return sum(distance_km(a,b) for a,b in zip(nodes,nodes[1:]))
    for _ in range(3):
        changed=False
        for i in range(len(result)-1):
            for j in range(i+2,len(result)+1):
                candidate=result[:i]+list(reversed(result[i:j]))+result[j:]
                if total(candidate)+1e-6<total(result):result=candidate;changed=True
        if not changed:break
    return result


class AddressSampler:
    def __init__(self, points, warehouses, rng):
        self.points=points;self.warehouses=warehouses;self.rng=rng
        self.groups=defaultdict(list);self.usage=defaultdict(int)
        for p in points:
            closest=min(warehouses,key=lambda w:distance_km(w,p))
            self.groups[closest['id']].append(p)
        if any(len(self.groups[w['id']])<25 for w in warehouses):
            raise ValueError('Missing address coverage near a warehouse')

    def choose(self, points):
        # Spread endpoints across real addresses before allowing realistic repeat visits.
        minimum=min(self.usage[p['source_url']] for p in points)
        point=self.rng.choice([p for p in points if self.usage[p['source_url']]==minimum])
        self.usage[point['source_url']]+=1
        return point

    def pair(self, warehouse_id):
        pickup=self.choose(self.groups[warehouse_id])
        choices=self.groups[warehouse_id] if self.rng.random()<.60 else self.points
        delivery=self.choose([p for p in choices if p['address']!=pickup['address'] and distance_km(p,pickup)>.25])
        return pickup,delivery
