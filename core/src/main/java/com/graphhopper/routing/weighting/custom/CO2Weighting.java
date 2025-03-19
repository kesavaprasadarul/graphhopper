    /*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.weighting.custom;

import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.CO2Engine;
import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;

import static com.graphhopper.routing.weighting.TurnCostProvider.NO_TURN_COST_PROVIDER;

/**
 * The CustomWeighting allows adjusting the edge weights relative to those we'd obtain for a given base flag encoder.
 * For example a car flag encoder already provides speeds and access flags for every edge depending on certain edge
 * properties. By default the CustomWeighting simply makes use of these values, but it is possible to adjust them by
 * setting up rules that apply changes depending on the edges' encoded values.
 * <p>
 * The formula for the edge weights is as follows:
 * <p>
 * weight = distance/speed + distance_costs + stress_costs
 * <p>
 * The first term simply corresponds to the time it takes to travel along the edge.
 * The second term adds a fixed per-distance cost that is proportional to the distance but *independent* of the edge
 * properties, i.e. it reads
 * <p>
 * distance_costs = distance * distance_influence
 * <p>
 * The third term is also proportional to the distance but compared to the second it describes additional costs that *do*
 * depend on the edge properties. It can represent any kind of costs that depend on the edge (like inconvenience or
 * dangers encountered on 'high-stress' roads for bikes, toll roads (because they cost money), stairs (because they are
 * awkward when going by bike) etc.). This 'stress' term reads
 * <p>
 * stress_costs = distance * stress_per_meter
 * <p>
 * and just like the distance term it describes costs measured in seconds. When modelling it, one always has to 'convert'
 * the costs into some time equivalent (e.g. for toll roads one has to think about how much money can be spent to save
 * a certain amount of time). Note that the distance_costs described by the second term in general cannot be properly
 * described by the stress costs, because the distance term allows increasing the per-distance costs per-se (regardless
 * of the type of the road). Also note that both the second and third term are different to the first in that they can
 * increase the edge costs but do *not* modify the travel *time*.
 * <p>
 * Instead of letting you set the speed directly, `CustomWeighting` allows changing the speed relative to the speed we
 * get from the base flag encoder. The stress costs can be specified by using a factor between 0 and 1 that is called
 * 'priority'.
 * <p>
 * Therefore the full edge weight formula reads:
 * <pre>
 * weight = distance / (base_speed * speed_factor * priority)
 *        + distance * distance_influence
 * </pre>
 * <p>
 * The open parameters that we can adjust are therefore: speed_factor, priority and distance_influence and they are
 * specified via the `{@link CustomModel}`. The speed can also be restricted to a maximum value, in which case the value
 * calculated via the speed_factor is simply overwritten. Edges that are not accessible according to the access flags of
 * the base vehicle always get assigned an infinite weight and this cannot be changed (yet) using this weighting.
 */
public final class CO2Weighting extends CustomWeighting {
    public static final String NAME = "carbon_weighting";

    /**
     * Converting to seconds is not necessary but makes adding other penalties easier (e.g. turn
     * costs or traffic light costs etc)
     */
    private CO2Engine engine;
    public CO2Weighting(TurnCostProvider turnCostProvider, CustomWeighting.Parameters parameters) {
        super(turnCostProvider, parameters);
        this.engine = new CO2Engine(new CO2Engine.CO2Profile());
        if (!Weighting.isValidName(getName()))
            throw new IllegalStateException("Not a valid name for a Weighting: " + getName());
        this.turnCostProvider = turnCostProvider;

        this.edgeToSpeedMapping = parameters.getEdgeToSpeedMapping();
        this.maxSpeedCalc = parameters.getMaxSpeedCalc();

        this.edgeToPriorityMapping = parameters.getEdgeToPriorityMapping();
        this.maxPrioCalc = parameters.getMaxPrioCalc();

        this.headingPenaltySeconds = parameters.getHeadingPenaltySeconds();

        // given unit is s/km -> convert to s/m
        this.distanceInfluence = parameters.getDistanceInfluence() / 1000.0;
        if (this.distanceInfluence < 0)
            throw new IllegalArgumentException("distance_influence cannot be negative " + this.distanceInfluence);
    }

    @Override
    public double calcMinWeightPerDistance() {
        return 1d / (maxSpeedCalc.calcMax() / SPEED_CONV) / maxPrioCalc.calcMax() + distanceInfluence;
    }

    private final int EARTH_RADIUS = 6371;
    private final double CO2_Baseline_9RD = 1110.2;
    private final double CO2_FACTOR_MULTIPLER = 0.65;
    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        var baseWeight = super.calcEdgeWeight(edgeState, reverse);
        final double distance = edgeState.getDistance();
        double speed = edgeToSpeedMapping.get(edgeState, reverse);
        double seconds = calcSeconds(distance, edgeState, reverse);
        var geometry = ((EdgeIteratorState) edgeState).fetchWayGeometry(FetchMode.TOWER_ONLY);
        var CO2 = CalculateAverageCO2(geometry, speed);
        var CO2Costs = (CO2 / CO2_Baseline_9RD) * CO2_FACTOR_MULTIPLER;
        return baseWeight + CO2Costs;
    }

    double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double lon1Rad = Math.toRadians(lon1);
        double lon2Rad = Math.toRadians(lon2);

        double x = (lon2Rad - lon1Rad) * Math.cos((lat1Rad + lat2Rad) / 2);
        double y = (lat2Rad - lat1Rad);

        return Math.sqrt(x * x + y * y) * EARTH_RADIUS;
    }
    double CalculateAverageCO2(PointList Geometry, double speed)
    {
        double accumulatedCO2 = 0;
        for (int i = 0; i < Geometry.size() - 1; i++) {
            var point1 = Geometry.get(i);
            var point2 = Geometry.get(i + 1);
            if(point1.isValid() && point2.isValid()) {
                var elevationDiff = point1.ele - point2.ele;
                var distance = calculateDistance(point1.getLat(), point2.getLat(), point1.getLon(), point2.getLon());
                var angleDeg = Math.toDegrees(Math.atan2(elevationDiff, distance));
                var duration = distance / speed * SPEED_CONV;
                accumulatedCO2 += engine.getFuelEmissionsCharacteristics(distance,angleDeg).get("co2_consumption_g");
            }
        }
        return accumulatedCO2;
    }

    double calcSeconds(double distance, EdgeIteratorState edgeState, boolean reverse) {
        double speed = edgeToSpeedMapping.get(edgeState, reverse);
        if (speed == 0)
            return Double.POSITIVE_INFINITY;
        if (speed < 0)
            throw new IllegalArgumentException("Speed cannot be negative");

        return distance / speed * SPEED_CONV;
    }

    @Override
    public long calcEdgeMillis(EdgeIteratorState edgeState, boolean reverse) {
        return Math.round(calcSeconds(edgeState.getDistance(), edgeState, reverse) * 1000);
    }

    @Override
    public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
        return turnCostProvider.calcTurnWeight(inEdge, viaNode, outEdge);
    }

    @Override
    public long calcTurnMillis(int inEdge, int viaNode, int outEdge) {
        return turnCostProvider.calcTurnMillis(inEdge, viaNode, outEdge);
    }

    @Override
    public boolean hasTurnCosts() {
        return turnCostProvider != NO_TURN_COST_PROVIDER;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @FunctionalInterface
    public interface EdgeToDoubleMapping {
        double get(EdgeIteratorState edge, boolean reverse);
    }

    @FunctionalInterface
    public interface MaxCalc {
        double calcMax();
    }

}
