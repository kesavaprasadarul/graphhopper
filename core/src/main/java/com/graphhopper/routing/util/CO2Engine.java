package com.graphhopper.routing.util;

import java.util.HashMap;
import java.util.Map;

public class CO2Engine {

    // Nested static class for CO2Profile
    public static class CO2Profile {
        public double idleFuelUsageL;
        public double massVehicle;
        public double massLoad;
        public double accelGravity;
        public double accelVehicle;
        public double wheelRadius;
        public double radiusTyre;
        public double torqueEngineMax;
        public double loadNominal;
        public double milagePerLiter;
        public double mileageModifier;
        public double displacementD13;
        public double axleRatioHighest;
        public double emissionCO2;
        public double dieselLToG;
        public int numWheels;
        public double tirePressure;
        public double rollingPressureRatio;

        public CO2Profile() {
            // Initial values based on Volvo FMX84
            this.idleFuelUsageL = 3.02;
            this.massVehicle = 32000;
            this.massLoad = 10000;
            this.accelGravity = 9.81;
            this.accelVehicle = 1;
            this.wheelRadius = 1;
            this.radiusTyre = 0.515;
            this.torqueEngineMax = 2600;
            this.loadNominal = 0.5;
            this.milagePerLiter = 5.4746;
            this.mileageModifier = 5;
            this.displacementD13 = 12.8;
            this.axleRatioHighest = 4.11;
            this.emissionCO2 = 3.17;
            this.dieselLToG = 850.8;
            this.numWheels = 6;
            this.tirePressure = 240;
            this.rollingPressureRatio = 0.01;
        }
    }

    private CO2Profile co2Profile;
    private double distanceAccumulatedM;
    private double co2AccumulatedG;
    private double fuelAccumulatedL;

    public CO2Engine(CO2Profile co2Profile) {
        this.co2Profile = co2Profile;
        this.distanceAccumulatedM = 0;
        this.co2AccumulatedG = 0;
        this.fuelAccumulatedL = 0;
    }

    // Resets the accumulated values to zero.
    public void reset() {
        this.distanceAccumulatedM = 0;
        this.co2AccumulatedG = 0;
        this.fuelAccumulatedL = 0;
    }

    // Calculates fuel and CO2 emissions characteristics based on the distance and slope angle.
    public Map<String, Double> getFuelEmissionsCharacteristics(double distanceM, double thetaDeg) {
        double massTotal = co2Profile.massVehicle + co2Profile.massLoad;
        double forceEngine = massTotal * co2Profile.accelVehicle;
        // forceWeight is calculated but not used further, as in the original code.
        double forceWeight = massTotal * co2Profile.accelGravity;
        double forceAngular = (massTotal * co2Profile.accelGravity * Math.sin(Math.toRadians(thetaDeg)))
                + (co2Profile.rollingPressureRatio * massTotal * co2Profile.accelGravity * Math.cos(Math.toRadians(thetaDeg)));
        double forceEngineMax = (co2Profile.torqueEngineMax * co2Profile.axleRatioHighest) / co2Profile.radiusTyre;
        double load = forceAngular / forceEngineMax;
        double loadOffset = load - co2Profile.loadNominal;
        double milageCompensated = Math.max(co2Profile.milagePerLiter - co2Profile.mileageModifier,
                (co2Profile.milagePerLiter - (loadOffset * co2Profile.mileageModifier)));
        double fuelConsumed = ((distanceM * 0.001) / milageCompensated);
        double co2Consumption = co2Profile.emissionCO2 * (fuelConsumed * co2Profile.dieselLToG);
        double fuelConsumedRaw = ((distanceM * 0.001) / co2Profile.milagePerLiter);
        double co2ConsumptionRaw = co2Profile.emissionCO2 * (fuelConsumedRaw * co2Profile.dieselLToG);

        Map<String, Double> result = new HashMap<>();
        result.put("co2_consumption_g", Math.max(0, co2Consumption));
        result.put("co2_consumption_raw", co2ConsumptionRaw);
        result.put("fuel_consumed_l", Math.max(0, fuelConsumed));
        result.put("fuel_consumed_raw", fuelConsumedRaw);
        result.put("distance", distanceM);
        result.put("theta", thetaDeg);
        result.put("load_off", loadOffset);

        return result;
    }

    // Updates the accumulated fuel consumption and CO2 emissions, then prints and returns the characteristics.
    public Map<String, Double> accumulateFuelConsumption(double distanceM, double thetaDeg, double duration) {
        this.distanceAccumulatedM += distanceM;
        Map<String, Double> co2Characteristics = getFuelEmissionsCharacteristics(distanceM, thetaDeg);
        this.fuelAccumulatedL += co2Characteristics.get("fuel_consumed_l");
        this.co2AccumulatedG = co2Profile.emissionCO2 * ((this.fuelAccumulatedL + (co2Profile.idleFuelUsageL * duration / 3600)) * co2Profile.dieselLToG);
        System.out.println(co2Characteristics);
        return co2Characteristics;
    }
}
