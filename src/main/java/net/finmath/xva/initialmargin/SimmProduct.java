package net.finmath.xva.initialmargin;

import net.finmath.exception.CalculationException;
import net.finmath.montecarlo.interestrate.LIBORModelMonteCarloSimulationInterface;
import net.finmath.montecarlo.interestrate.products.AbstractLIBORMonteCarloProduct;
import net.finmath.stochastic.RandomVariableInterface;
import net.finmath.stochastic.Scalar;
import net.finmath.xva.coordinates.simm2.MarginType;
import net.finmath.xva.coordinates.simm2.ProductClass;
import net.finmath.xva.coordinates.simm2.RiskClass;
import net.finmath.xva.coordinates.simm2.Simm2Coordinate;
import net.finmath.xva.sensitivityproviders.timelines.SimmSensitivityTimeline;
import org.apache.commons.lang3.tuple.Pair;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A product whose value represents the total initial margin to be posted at a fixed time according to SIMM.
 */
public class SimmProduct extends AbstractLIBORMonteCarloProduct {
	private SimmSensitivityTimeline timeline;
	private double marginCalculationTime;
	private SimmModality modality;
	private SimmNonIRDeltaAndVegaScheme nonIRDeltaAndVegaScheme;

	public SimmProduct(double marginCalculationTime, SimmSensitivityTimeline provider, SimmModality modality) {
		this.modality = modality;
		this.marginCalculationTime = marginCalculationTime;
		this.timeline = provider;
		this.nonIRDeltaAndVegaScheme = new SimmNonIRDeltaAndVegaScheme(modality);
	}

	public RandomVariableInterface getValue(double evaluationTime, LIBORModelMonteCarloSimulationInterface model) throws CalculationException {
		if (evaluationTime > marginCalculationTime) {
			return model.getRandomVariableForConstant(0.0);
		}

		final RandomVariableInterface simmValue = timeline.getSimmSensitivities(evaluationTime, model).entrySet().stream().
				collect(Collectors.groupingBy(e -> e.getKey().getProductClass())).entrySet().stream().
				map(group -> getSimmForProductClass(group.getKey(), group.getValue())).
				reduce(model.getRandomVariableForConstant(0.0), RandomVariableInterface::add);

		RandomVariableInterface numeraireAtEval = model.getNumeraire(evaluationTime);
		return simmValue.sub(this.getModality().getPostingThreshold()).floor(0.0).mult(numeraireAtEval);
	}

	private RandomVariableInterface getSimmForProductClass(ProductClass productClass, List<Map.Entry<Simm2Coordinate, RandomVariableInterface>> sensitivities) {
		final Map<RiskClass, RandomVariableInterface> marginByRiskClass = sensitivities.stream().
				collect(Collectors.groupingBy(e -> e.getKey().getRiskClass())).entrySet().stream().
				map(group -> Pair.of(group.getKey(), getSimmForRiskClass(group.getKey(), group.getValue()))).
				collect(Collectors.toMap(Pair::getKey, Pair::getValue));

		//TODO cross risk class aggregate
		return marginByRiskClass.values().stream().reduce(new Scalar(0.0), RandomVariableInterface::add);
	}

	private RandomVariableInterface getSimmForRiskClass(RiskClass riskClass, List<Map.Entry<Simm2Coordinate, RandomVariableInterface>> sensitivities) {
		return sensitivities.stream().
				collect(Collectors.groupingBy(e -> e.getKey().getRiskType())).entrySet().stream().
				map(group -> {
					switch (group.getKey()) {
						case DELTA:
							if (riskClass == RiskClass.INTEREST_RATE) {
								return new Scalar(0.0); //TODO IR Delta
							}
							return nonIRDeltaAndVegaScheme.getValue(riskClass, sensitivities);
						case VEGA:
							return nonIRDeltaAndVegaScheme.getValue(riskClass, sensitivities);
						default:
							return new Scalar(0.0); //TODO Curvature/BaseCorr
					}
				}).
				reduce(new Scalar(0.0), RandomVariableInterface::add);
	}

	public SimmModality getModality() {
		return modality;
	}
}
