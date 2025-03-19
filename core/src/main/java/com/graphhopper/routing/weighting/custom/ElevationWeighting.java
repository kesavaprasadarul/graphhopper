package com.graphhopper.routing.weighting.custom;

import com.graphhopper.routing.util.CO2Engine;
import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;

import static com.graphhopper.routing.weighting.TurnCostProvider.NO_TURN_COST_PROVIDER;

public final class ElevationWeighting extends CustomWeighting {
    public static final String NAME = "elevation_weighting";

    /**
     * Converting to seconds is not necessary but makes adding other penalties easier (e.g. turn
     * costs or traffic light costs etc)
     */
    public ElevationWeighting(TurnCostProvider turnCostProvider, CustomWeighting.Parameters parameters) {
        super(turnCostProvider, parameters);
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

    private final double ELEVATION_FACTOR_MULTIPLER = 0.65;
    private final double Elevation_Baseline = 7.13;

    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        var baseWeight = super.calcEdgeWeight(edgeState, reverse);
        var geometry = ((EdgeIteratorState) edgeState).fetchWayGeometry(FetchMode.TOWER_ONLY);
        var accElevation = CalculateElevation(geometry);
        var accElevationCosts = (accElevation / Elevation_Baseline) * ELEVATION_FACTOR_MULTIPLER;
        return Math.max(0, baseWeight + accElevationCosts);
    }

    double CalculateElevation(PointList Geometry)
    {
        double deltaElevation = 0;
        for (int i = 0; i < Geometry.size() - 1; i++) {
            var point1 = Geometry.get(i);
            var point2 = Geometry.get(i + 1);
            if(point1.isValid() && point2.isValid()) {
                deltaElevation += point1.ele - point2.ele;
            }
        }
        return deltaElevation;
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
