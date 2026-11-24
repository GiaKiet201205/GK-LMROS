"""Rolling-horizon insertion with frozen progress and mandatory warehouse handoff.

Pickup trips and delivery trips are separate. Pickups are unloaded only at
the final warehouse checkpoint; they become deliveries in a later trip.
This is a constrained heuristic, not a proof of the global optimum.
"""
import copy
import math
import os
import time

import httpx


def haversine_m(a, b):
    p, q = math.radians(a['lat']), math.radians(b['lat'])
    x = math.sin((q-p)/2)**2 + math.cos(p)*math.cos(q)*math.sin(math.radians(b['lng']-a['lng'])/2)**2
    return 12742000 * math.asin(math.sqrt(min(1, x)))


class Travel:
    def __init__(self, points, provider=None):
        self.provider = provider or os.getenv('ROUTING_PROVIDER', 'estimate')
        self.points = list({self.key(p): p for p in points}.values())
        self.edges = {}
        if self.provider == 'google':
            self._google()
        elif self.provider != 'estimate':
            raise ValueError('Unsupported ROUTING_PROVIDER')

    @staticmethod
    def key(p):
        return (round(float(p['lat']), 6), round(float(p['lng']), 6))

    def _google(self):
        key = os.getenv('GOOGLE_ROUTES_API_KEY', '')
        if not key:
            raise ValueError('Google Routes API key is not configured')
        maximum = int(os.getenv('GOOGLE_MATRIX_MAX_POINTS', '100'))
        if len(self.points) > maximum:
            raise ValueError('Route matrix exceeds configured Google point budget; reduce the active fleet or increase the configured limit')
        # 25 x 25 <= 625 elements; no silent fallback on billing/key/quota errors.
        with httpx.Client(timeout=5) as client:
            for i in range(0, len(self.points), 25):
                origins = self.points[i:i+25]
                for j in range(0, len(self.points), 25):
                    destinations = self.points[j:j+25]
                    waypoint = lambda p: {'waypoint': {'location': {'latLng': {'latitude': p['lat'], 'longitude': p['lng']}}}}
                    response = client.post('https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix',
                        headers={'X-Goog-Api-Key': key, 'X-Goog-FieldMask': 'originIndex,destinationIndex,status,condition,distanceMeters,duration'},
                        json={'origins': [waypoint(p) for p in origins], 'destinations': [waypoint(p) for p in destinations],
                              'travelMode': 'DRIVE', 'routingPreference': 'TRAFFIC_UNAWARE'})
                    if response.status_code != 200:
                        raise ValueError(f'Google Routes request failed ({response.status_code}); check credentials, billing and quota')
                    for element in response.json():
                        a, b = origins[element.get('originIndex', 0)], destinations[element.get('destinationIndex', 0)]
                        if element.get('status', {}).get('code', 0) or element.get('condition') != 'ROUTE_EXISTS':
                            self.edges[self.key(a), self.key(b)] = (math.inf, math.inf)
                        else:
                            self.edges[self.key(a), self.key(b)] = (float(element.get('distanceMeters', 0)), float(element['duration'].removesuffix('s')) / 60)

    def edge(self, a, b):
        if self.key(a) == self.key(b):
            return 0., 0.
        if self.provider == 'google':
            return self.edges.get((self.key(a), self.key(b)), (math.inf, math.inf))
        metres = haversine_m(a, b) * 1.35
        return metres, metres / 500.  # 30 km/h. Explicit estimate in API/UI.


def evaluate(route, nodes, travel, failure=None):
    """Remaining cost only; completed prefix is never evaluated or reinserted."""
    def reject(reason, **details):
        if failure is not None:
            failure.update(reason=reason, **details)
        return None
    kg, volume = float(route.get('onboard_kg', 0)), float(route.get('onboard_m3', 0))
    if route['status'] == 'planned':
        kg = sum(float(n.get('weight_kg', 0)) for n in nodes if n['kind'] in ('delivery', 'return'))
        volume = sum(float(n.get('volume_m3', 0)) for n in nodes if n['kind'] in ('delivery', 'return'))
    capacity, capacity_m3 = route['capacity_kg'], route['capacity_m3']
    if kg > capacity + 1e-6:
        return reject('weight_capacity', load_kg=kg, capacity_kg=capacity)
    if volume > capacity_m3 + 1e-6:
        return reject('volume_capacity', load_m3=volume, capacity_m3=capacity_m3)
    distance, at, previous, schedule = 0., route['start']['time_min'], route['start'], []
    for node in nodes:
        metres, minutes = travel.edge(previous, node)
        if not math.isfinite(metres) or not math.isfinite(minutes):
            return reject('road_unreachable')
        distance += metres
        at = max(at + minutes, float(node.get('tw_start_min') or 0))
        if at > float(node.get('tw_end_min') or math.inf) + 1e-6:
            return reject('time_window_unreachable', arrival_min=at, deadline_min=node['tw_end_min'])
        schedule.append(at)
        at += float(node.get('service_min', 0))
        if node['kind'] == 'pickup':
            kg += node['weight_kg']; volume += node['volume_m3']
        elif node['kind'] in ('delivery', 'return'):
            kg -= node['weight_kg']; volume -= node['volume_m3']
        if kg > capacity + 1e-6:
            return reject('weight_capacity', load_kg=kg, capacity_kg=capacity)
        if volume > capacity_m3 + 1e-6:
            return reject('volume_capacity', load_m3=volume, capacity_m3=capacity_m3)
        if kg < -1e-6 or volume < -1e-6:
            return reject('invalid_onboard_load')
        previous = node
    if at > route.get('shift_end_min', math.inf):
        return reject('shift_end_exceeded', finish_min=at, shift_end_min=route['shift_end_min'])
    return distance, at, schedule


def improve(route, travel, deadline):
    nodes = route['nodes']
    current = evaluate(route, nodes, travel)
    if current is None:
        return
    # Keep en-route / immutable pending prefix and final warehouse in place.
    first = int(route.get('frozen_count', 0))
    for _ in range(2):
        changed = False
        for a in range(first, len(nodes)-2):
            for b in range(a+1, len(nodes)-1):
                if time.monotonic() > deadline:
                    return
                candidate = nodes[:a] + list(reversed(nodes[a:b+1])) + nodes[b+1:]
                result = evaluate(route, candidate, travel)
                if result and result[0] < current[0] - .01:
                    nodes[:] = candidate; current = result; changed = True
        if not changed:
            break



def insert_batch(route, customers, travel, deadline, maximum):
    """Try the whole batch atomically; never keep a partially inserted batch."""
    existing = [n for n in route['nodes'] if n['kind'] != 'warehouse']
    if len(existing) + len(customers) > maximum:
        return None
    all_customers = existing + customers
    if sum(float(n.get('weight_kg', 0)) for n in all_customers) > route['capacity_kg'] + 1e-6:
        return None
    if sum(float(n.get('volume_m3', 0)) for n in all_customers) > route['capacity_m3'] + 1e-6:
        return None
    best = None
    # Distinct orders help tight appointment windows without changing constraints.
    variants = [customers, sorted(customers, key=lambda n: (n['tw_end_min'], n.get('id', 0)))]
    for ordered in variants:
        nodes = list(route['nodes'])
        for customer in ordered:
            if time.monotonic() >= deadline:
                return best
            candidates = []
            for position in range(len(nodes)):
                if time.monotonic() >= deadline:
                    return best
                candidate = nodes[:position] + [customer] + nodes[position:]
                result = evaluate(route, candidate, travel)
                if result:
                    candidates.append(((result[0], result[1], position), candidate, result))
            if not candidates:
                break
            _, nodes, result = min(candidates, key=lambda c: c[0])
        else:
            result = evaluate(route, nodes, travel)
            if result and (best is None or (result[0], result[1]) < (best[1][0], best[1][1])):
                best = (nodes, result)
    return best


def pack_single_batches(routes, ordered, travel, req, deadline, maximum):
    """If a whole warehouse/phase batch fits one van, keep it in one trip."""
    groups = {}
    for order in ordered:
        if order['tw_end_min'] >= req['now_min']:
            groups.setdefault((order['warehouse_id'], order['kind']), []).append(order)
    packed = set()
    for (warehouse, phase), customers in groups.items():
        if len(customers) > maximum:
            continue
        best = None
        candidates = sorted(enumerate(routes), key=lambda pair: (pair[1]['cost_per_km'], -pair[1]['capacity_kg'], pair[1]['vehicle_id']))
        for index, route in candidates:
            if time.monotonic() >= deadline:
                break
            if route['warehouse_id'] != warehouse or route['id'] is not None or route['status'] != 'planned' or len(route['nodes']) != 1 or route.get('allow_additions') is False:
                continue
            result = insert_batch(route, customers, travel, deadline, maximum)
            if result:
                nodes, values = result
                score = (values[0] / 1000 * route['cost_per_km'], values[1], values[0], route['vehicle_id'])
                if best is None or score < best[0]:
                    best = (score, index, nodes)
        if best:
            _, index, nodes = best
            routes[index]['nodes'] = nodes
            routes[index]['phase'] = phase
            packed.update(c['id'] for c in customers)
    return packed


def improve_fleet(routes, travel, req, deadline):
    """Consolidate fresh trips first, then reduce cost without increasing trip count."""
    fixed = float(req.get('fixed_trip_cost', 35000))
    maximum = min(45, int(req.get('max_orders', 45)))
    def cost(route, nodes, result):
        return result[0] / 1000 * float(route['cost_per_km']) + (fixed if any(n['kind'] != 'warehouse' for n in nodes) else 0)
    eligible = [r for r in routes if r['id'] is None and r['status'] == 'planned' and not r.get('frozen_count')]
    # Moving a whole small trip is atomic, even when individual moves would
    # increase distance before its final customer empties the source van.
    for source in sorted(eligible, key=lambda r: len(r['nodes'])):
        customers = [n for n in source['nodes'] if n['kind'] != 'warehouse']
        if not customers:
            continue
        best = None
        for target in eligible:
            if time.monotonic() >= deadline:
                return
            if target is source or len(target['nodes']) <= 1 or target['warehouse_id'] != source['warehouse_id'] or target.get('phase') != source.get('phase'):
                continue
            result = insert_batch(target, customers, travel, deadline, maximum)
            if result:
                nodes, values = result
                score = (cost(target, nodes, values), values[1], target['vehicle_id'])
                if best is None or score < best[0]:
                    best = (score, target, nodes)
        if best:
            _, target, nodes = best
            target['nodes'] = nodes
            source['nodes'] = [source['nodes'][-1]]
    for _ in range(2):
        changed = False
        for source in sorted(eligible, key=lambda r: -float(r['cost_per_km'])):
            for node in list(source['nodes'][:-1]):
                if time.monotonic() >= deadline: return
                old_source = evaluate(source, source['nodes'], travel)
                if old_source is None: continue
                reduced = [n for n in source['nodes'] if n is not node]
                new_source = evaluate(source, reduced, travel)
                if new_source is None: continue
                best = None
                for target in eligible:
                    if target is source or target['warehouse_id'] != source['warehouse_id'] or target.get('phase') not in (None, node['kind']): continue
                    if len(target['nodes']) - 1 >= min(maximum, int(target.get('max_orders', maximum))): continue
                    old_target = evaluate(target, target['nodes'], travel)
                    if old_target is None: continue
                    before = cost(source, source['nodes'], old_source) + cost(target, target['nodes'], old_target)
                    for position in range(len(target['nodes'])):
                        if time.monotonic() >= deadline: return
                        candidate = target['nodes'][:position] + [node] + target['nodes'][position:]
                        result = evaluate(target, candidate, travel)
                        if result is None: continue
                        saving = before - cost(source, reduced, new_source) - cost(target, candidate, result)
                        before_trips = int(len(source['nodes']) > 1) + int(len(target['nodes']) > 1)
                        after_trips = int(len(reduced) > 1) + int(len(candidate) > 1)
                        score = (before_trips - after_trips, saving)
                        if (score[0] > 0 or score[0] == 0 and saving > .01) and (best is None or score > best[0]): best = (score, target, candidate)
                if best:
                    _, target, candidate = best
                    source['nodes'] = reduced; target['nodes'] = candidate; target['phase'] = node['kind']; changed = True
        if not changed: break


def solve(req):
    began = time.monotonic()
    deadline = began + min(10., float(req.get('seconds', 3)))
    routes = copy.deepcopy(req.get('routes', []))
    warehouses = {w['id']: w for w in req['warehouses']}
    vehicles = {v['id']: v for v in req['vehicles']}
    active = {r['vehicle_id'] for r in routes}
    driving = {r['vehicle_id'] for r in routes if r['status'] == 'in_progress'}
    # Old demo/overdue routes are preserved in the database and keep their van
    # occupied, but do not consume a Google matrix or accept new work.
    blocked = [r for r in routes if any(n['kind'] != 'warehouse' and n.get('tw_end_min') is not None and n['tw_end_min'] < req['now_min'] for n in r['nodes'])]
    routes = [r for r in routes if r not in blocked]
    # Only one new trip per free vehicle in this round. Busy vehicles can take
    # pickups on their existing route; ready deliveries wait for a depot start.
    for vehicle in vehicles.values():
        if vehicle['id'] in active or vehicle.get('allocation_enabled') is False:
            continue
        warehouse = warehouses[vehicle['warehouse_id']]
        routes.append({'id': None, 'vehicle_id': vehicle['id'], 'warehouse_id': warehouse['id'], 'status': 'planned',
                       'capacity_kg': vehicle['capacity_kg'], 'capacity_m3': vehicle['capacity_m3'],
                       'cost_per_km': vehicle['cost_per_km'], 'shift_end_min': vehicle['shift_end_min'], 'phase': None,
                       'start': {**warehouse, 'time_min': max(req['now_min'], vehicle.get('shift_start_min', req['now_min']))}, 'frozen_count': 0,
                       'nodes': [{**warehouse, 'kind': 'warehouse', 'stop_id': None, 'service_min': 5}]})
    points = [r['start'] for r in routes] + [n for r in routes for n in r['nodes']]
    points += req['orders']
    travel = Travel(points, req.get('provider'))
    deadline = time.monotonic() + min(10., float(req.get('seconds', 3)))
    original = {r['id']: [n.get('stop_id') for n in r['nodes']] for r in routes if r['id'] is not None}
    assigned, unassigned = [], []
    max_stops = int(req.get('max_stops', 101))
    max_orders = min(45, int(req.get('max_orders', 45)))
    def priority(order):
        if req.get('group_deliveries'):
            # Seed remote neighbourhoods first, then prefer cheap insertions in
            # already-open tours. Time windows remain hard constraints.
            return (order['tw_end_min'] // 1440, -haversine_m(warehouses[order['warehouse_id']], order), order['id'])
        return (order['tw_end_min'], order['id'])
    ordered = sorted(req['orders'], key=priority)
    if req.get('group_deliveries'):
        # Alternating distant seeds can fill every vehicle with customers on
        # opposite sides of the depot. Visit the seed's nearby customers first
        # before opening the next spatial batch; feasibility is still checked
        # at every insertion, rather than assuming the whole batch fits.
        remaining, ordered = ordered, []
        while remaining:
            current = remaining.pop(0)
            ordered.append(current)
            for _ in range(max_orders - 1):
                nearby = [(haversine_m(current, candidate), candidate['tw_end_min'], candidate['id'], index)
                          for index, candidate in enumerate(remaining) if candidate['warehouse_id'] == current['warehouse_id']]
                if not nearby:
                    break
                current = remaining.pop(min(nearby)[-1])
                ordered.append(current)
    # Reserve time for remaining insertions; whole-batch packing is a fast path,
    # not a replacement for capacity/appointment/shift checks.
    packed = pack_single_batches(routes, ordered, travel, req, min(deadline, time.monotonic() + float(req.get('seconds', 3)) * .4), min(max_orders, max_stops - 1)) if req.get('group_deliveries') else set()
    assigned.extend(o['id'] for o in ordered if o['id'] in packed)
    # Stable order for reproducible reroutes; every insertion checks hard windows.
    for order in ordered:
        if order['id'] in packed:
            continue
        if float(order.get('tw_end_min', math.inf)) + 1e-6 < req['now_min']:
            unassigned.append({'id': order['id'], 'reason': 'order_deadline_passed', 'deadline_min': order['tw_end_min']})
            continue
        if time.monotonic() > deadline:
            unassigned.append({'id': order['id'], 'reason': 'time_budget'})
            continue
        best, failures = None, []
        for index, route in enumerate(routes):
            if time.monotonic() > deadline:
                break
            if route['warehouse_id'] != order['warehouse_id']:
                continue
            if len(route['nodes']) >= max_stops:
                failures.append({'reason': 'order_limit'})
                continue
            if route.get('allow_additions') is False:
                continue
            customer_count = sum(n['kind'] != 'warehouse' for n in route['nodes']) + int(route.get('completed_customers', 0))
            if customer_count >= min(45, int(route.get('max_orders', max_orders))):
                failures.append({'reason': 'order_limit'})
                continue
            if route.get('phase') not in (None, order['kind']):
                continue
            if route['status'] == 'planned' and route['vehicle_id'] in driving:
                continue
            if order['kind'] in ('delivery', 'return') and route['status'] != 'planned':
                continue  # Goods still at the warehouse cannot appear on a moving van.
            baseline = evaluate(route, route['nodes'], travel)
            if baseline is None:
                failures.append({'reason': 'existing_route_infeasible'})
                continue  # Preserve an already-infeasible legacy route, do not add work.
            for position in range(route.get('frozen_count', 0), len(route['nodes'])):
                if time.monotonic() > deadline:
                    break
                candidate = route['nodes'][:position] + [order] + route['nodes'][position:]
                failure = {}
                result = evaluate(route, candidate, travel, failure)
                if result is None:
                    failures.append(failure)
                    continue
                incremental = (result[0] - baseline[0]) / 1000 * float(route['cost_per_km'])
                if req.get('group_deliveries') and customer_count == 0:
                    incremental += float(req.get('fixed_trip_cost', 35000))
                score = ((int(customer_count == 0), incremental, result[1], result[0], route['vehicle_id'], position) if req.get('group_deliveries') else (incremental, result[0], route['vehicle_id'], position))
                if best is None or score < best[0]:
                    best = (score, index, candidate)
        if best:
            _, index, nodes = best
            routes[index]['nodes'] = nodes
            routes[index]['phase'] = order['kind']
            assigned.append(order['id'])
        else:
            # Only report a specific hard constraint if every attempted
            # insertion failed for that same reason; mixed failures stay explicit.
            reasons = {f['reason'] for f in failures}
            info = failures[0] if len(reasons) == 1 else {'reason': 'multiple_constraints' if failures else 'no_eligible_route'}
            if time.monotonic() > deadline:
                info = {'reason': 'time_budget'}
            unassigned.append({'id': order['id'], **info})
    if req.get('group_deliveries'):
        improve_fleet(routes, travel, req, deadline)
    output = []
    for route in routes:
        if route['id'] is None and len(route['nodes']) == 1:
            continue
        improve(route, travel, deadline)
        result = evaluate(route, route['nodes'], travel)
        if result is None:
            continue
        route['distance_km'] = round(result[0]/1000, 3)
        route['end_min'] = result[1]
        customers = [n for n in route['nodes'] if n['kind'] != 'warehouse']
        route['load_kg'] = round(sum(float(n.get('weight_kg', 0)) for n in customers), 3)
        route['load_m3'] = round(sum(float(n.get('volume_m3', 0)) for n in customers), 4)
        route['operating_cost'] = round(result[0] / 1000 * float(route['cost_per_km']) + (float(req.get('fixed_trip_cost', 35000)) if req.get('group_deliveries') else 0))
        fare_customers = [n for n in customers if n['kind'] == 'delivery']
        route['confirmed_revenue'] = sum(int(n['shipping_fee_vnd']) for n in fare_customers if n.get('shipping_fee_vnd') is not None and n.get('shipping_price_status') == 'confirmed')
        route['estimated_revenue'] = sum(int(n['shipping_fee_vnd']) for n in fare_customers if n.get('shipping_fee_vnd') is not None)
        route['unpriced_orders'] = sum(n.get('shipping_fee_vnd') is None for n in fare_customers)
        route['estimated_fares'] = sum(n.get('shipping_price_status') == 'estimated' for n in fare_customers)
        route['delivery_contribution'] = None if route['unpriced_orders'] else route['estimated_revenue'] - route['operating_cost']
        for node, at in zip(route['nodes'], result[2]):
            node['arrive_min'] = at
        route['changed'] = route['id'] is None or original[route['id']] != [n.get('stop_id') for n in route['nodes']]
        output.append(route)
    return {'routes': output, 'stats': {'algorithm': 'batch-first-insertion-2opt-relocate-v2', 'planning_objective': 'min_trips_then_cost' if req.get('group_deliveries') else 'incremental_cost', 'single_batch_orders': len(packed), 'source': travel.provider,
            'assigned': len(assigned), 'unassigned': unassigned, 'requested': len(req['orders']),
            'remaining_km': round(sum(r['distance_km'] for r in output), 3), 'blocked_routes': [r['id'] for r in blocked],
            'estimated_cost': round(sum(r['distance_km'] * float(r['cost_per_km']) + (float(req.get('fixed_trip_cost', 35000)) if req.get('group_deliveries') else 0) for r in output), 0),
            'max_orders': max_orders,
            'confirmed_revenue': sum(r['confirmed_revenue'] for r in output),
            'estimated_revenue': sum(r['estimated_revenue'] for r in output),
            'unpriced_orders': sum(r['unpriced_orders'] for r in output),
            'estimated_fares': sum(r['estimated_fares'] for r in output),
            'estimated_positions': sum(r.get('position_source') == 'last_confirmed_position' for r in output),
            'solve_seconds': round(time.monotonic()-began, 3)}}
