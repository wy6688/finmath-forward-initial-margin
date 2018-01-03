package net.finmath.initialmargin.isdasimm.sensitivity;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import net.finmath.exception.CalculationException;
import net.finmath.initialmargin.isdasimm.changedfinmath.LIBORModelMonteCarloSimulationInterface;
import net.finmath.initialmargin.isdasimm.products.AbstractSIMMProduct;
import net.finmath.marketdata.model.curves.DiscountCurve;
import net.finmath.marketdata.model.curves.DiscountCurveInterface;
import net.finmath.montecarlo.RandomVariable;
import net.finmath.montecarlo.automaticdifferentiation.RandomVariableDifferentiableInterface;
import net.finmath.optimizer.SolverException;
import net.finmath.stochastic.RandomVariableInterface;

/** This class contains some functions and methods which we need to calculate forward initial margin.
 * 
 * @author Mario Viehmann
 *
 */
public abstract class AbstractSIMMSensitivityCalculation {

	public boolean isUseTimeGridAdjustment; 
	public boolean isUseAnalyticSwapSensitivities;
	public boolean isConsiderOISSensitivities;
	public static double secondsPseudoInverse = 0;

	public enum SensitivityMode{
		LinearMelting,    // Melting of sensitivties to zero until final maturity
		Interpolation,    // Interpolate OIS and Forward curve sensitivities between time points of exact AAD sensitivities
		InterpolationOIS,
		Exact,            // AAD or Analytic (for Swaps)
		ExactConsideringDependencies
	}

	protected SensitivityMode sensitivityMode;

	public enum WeightMode{
		Constant,  //Sets dL/dS(t=0) for all forward IM times, i.e. leave the weight adjustment dL/dS constant
		Stochastic //Calculate dL/dS(t) for all forward IM times, i.e. (weakly) stochastic weight adjustment 
	}

	public static final RandomVariableInterface[] zeroBucketsIR = IntStream.range(0, 12 /*IRMaturityBuckets.length*/).mapToObj(i->new RandomVariable(0.0)).toArray(RandomVariableInterface[]::new);

	private WeightMode liborWeightMethod;  
	private HashMap<Double /*time*/, RandomVariableInterface[][]> riskWeightMap = new HashMap<>(); // Contains the weights for conversion from model sensitivities to market sensitivities.
	/*
	 * Reference for sensitivity cache in case OIS - Libor dependencies are considered. In this case we must calculate 
	 * (dV/dS_{LIBOR}, dV/dS_{OIS}) at a given evaluation time at once (and not dV/dS_{LIBOR}, dV/dS_{OIS} separately).
	 */
	private SoftReference<Map<Double, Map<String, RandomVariableInterface[]>>> sensitivityCacheReference = null; 


	/**
	 * 
	 * @param sensitivityMode
	 * @param liborWeightMode
	 * @param isUseTimeGridAdjustment
	 * @param isUseAnalyticSwapSensitivities
	 * @param isConsiderOISSensitivities
	 */
	public AbstractSIMMSensitivityCalculation(SensitivityMode sensitivityMode, WeightMode liborWeightMode, 
			boolean isUseTimeGridAdjustment, boolean isUseAnalyticSwapSensitivities, boolean isConsiderOISSensitivities){
		this.sensitivityMode = sensitivityMode;
		this.liborWeightMethod = liborWeightMode;
		this.isUseTimeGridAdjustment = isUseTimeGridAdjustment;
		this.isUseAnalyticSwapSensitivities = isUseAnalyticSwapSensitivities;
		this.isConsiderOISSensitivities = isConsiderOISSensitivities;   	
	}

	/**
	 * 
	 * @param sensitivityMode
	 * @param liborWeightMode
	 * @param interpolationStep
	 */
	public AbstractSIMMSensitivityCalculation(SensitivityMode sensitivityMode, WeightMode liborWeightMode, double interpolationStep){
		this(sensitivityMode, liborWeightMode, false, true, true);
	}


	/** Calculate the delta SIMM sensitivities for a given risk class and index curve at a given evaluation time with the specified Libor market model.
	 *  The sensitivities are calculated by interpolation or melting (a particular case of interpolation).
	 * 		
	 * @param product The product 
	 * @param riskClass The risk class of the product 
	 * @param curveIndexName The name of the index curve
	 * @param evaluationTime The time at which the sensitivities should be calculated
	 * @param model The Libor market model
	 * @return The sensitivities at evaluationTime on the SIMM buckets (or on Libor Buckets)
	 * @throws SolverException
	 * @throws CloneNotSupportedException
	 * @throws CalculationException
	 */
	public abstract RandomVariableInterface[] getDeltaSensitivities(AbstractSIMMProduct product,
			String riskClass, 
			String curveIndexName,
			double evaluationTime, 
			LIBORModelMonteCarloSimulationInterface model) throws SolverException, CloneNotSupportedException, CalculationException;


	/**
	 * 
	 * @param product The product
	 * @param curveIndexName The name of the curve
	 * @param riskClass The risk class for the sensitivity calculation
	 * @param evaluationTime The time at which the sensitivities should be calculated
	 * @param model The Libor market model
	 * @return The delta sensitivities calculated by AAD
	 * @throws SolverException
	 * @throws CloneNotSupportedException
	 * @throws CalculationException
	 */
	public abstract RandomVariableInterface[] getExactDeltaSensitivities(AbstractSIMMProduct product,
			String curveIndexName,
			String riskClass,
			double evaluationTime, 
			LIBORModelMonteCarloSimulationInterface model) throws SolverException, CloneNotSupportedException, CalculationException;

	/** Get the sensitivities using sensitivity melting on SIMM Buckets. 
	 * 
	 * @param product
	 * @param sensitivities
	 * @param meltingZeroTime
	 * @param evaluationTime
	 * @param curveIndexName
	 * @param riskClass
	 * @return
	 * @throws SolverException
	 * @throws CloneNotSupportedException
	 * @throws CalculationException
	 */
	public abstract RandomVariableInterface[] getMeltedSensitivities(AbstractSIMMProduct product, RandomVariableInterface[] sensitivities, double meltingZeroTime,
			double evaluationTime, String curveIndexName, String riskClass) throws SolverException, CloneNotSupportedException, CalculationException;


	/**Calculate the sensitivities dV/dS with respect to all swap rates for given product and curve. This applies to the risk class Interest Rates only.
	 * 
	 * @param product The SIMM product
	 * @param curveIndexName The name of the curve to be considered (OIS, LiborXm)
	 * @param evaluationTime The time at which the initial margin is calculated
	 * @param model The Libor market model
	 * @return The sensitivities dV/dS i.e. with respect to swap rates.
	 * @throws SolverException
	 * @throws CloneNotSupportedException
	 * @throws CalculationException
	 * 
	 */
	public RandomVariableInterface[] doCalculateDeltaSensitivitiesIR(AbstractSIMMProduct product,
			String curveIndexName, 
			double evaluationTime,
			LIBORModelMonteCarloSimulationInterface model) throws SolverException, CloneNotSupportedException, CalculationException{

		RandomVariableInterface[] delta = null; // The vector of delta sensitivities on all SIMM Buckets

		switch(curveIndexName){

		case("Libor6m"):

			RandomVariableInterface[] dVdL = product.getValueLiborSensitivities(evaluationTime, model);

		// Calculate dV/dS = dV/dL * dL/dS
		delta = mapLiborToMarketRateSensitivities(evaluationTime, dVdL, model);  

		// Map Sensitivities on SIMM Buckets
		delta = mapSensitivitiesOnBuckets(delta, "InterestRate" /*riskClass*/, null, model);		       

		break;

		case("OIS"):

			if(isConsiderOISSensitivities) {

				// Calculate dV/dS = dV/dP * dP/dS. These Sensis are already on SIMM Buckets
				delta = product.getDiscountCurveSensitivities("InterestRate" /*riskClass*/,evaluationTime, model); 

			} else delta = zeroBucketsIR;

		break;

		default:
			throw new IllegalArgumentException("Unknow curve: " + curveIndexName);
		}

		return delta;
	}

	public RandomVariableInterface[] doCalculateDeltaSensitivitiesOISLiborDependence(AbstractSIMMProduct product,
			String curveIndexName, 
			double evaluationTime,
			LIBORModelMonteCarloSimulationInterface model) throws SolverException, CloneNotSupportedException, CalculationException{

		RandomVariableInterface[] deltaSensitivitiesOfCurve = null;
		Map<Double, Map<String,RandomVariableInterface[]>> sensitivityCache = sensitivityCacheReference != null ? sensitivityCacheReference.get() : null;
		if(sensitivityCache == null) {
			sensitivityCache = new ConcurrentHashMap<Double, Map<String,RandomVariableInterface[]>>();
			sensitivityCacheReference = new SoftReference<Map<Double,Map<String,RandomVariableInterface[]>>>(sensitivityCache);
		}
		if(sensitivityCache.get(evaluationTime)==null) {
			Map<String, RandomVariableInterface[]> deltaSensitivitiesMap = getMarketRateSensitivities(product, evaluationTime, model);
			sensitivityCache.put(evaluationTime, deltaSensitivitiesMap);
		}

		switch(curveIndexName){

		case("Libor6m"): deltaSensitivitiesOfCurve = sensitivityCache.get(evaluationTime).get("Libor6m");

		break;

		case("OIS"): 

			if(isConsiderOISSensitivities) {

				deltaSensitivitiesOfCurve = sensitivityCache.get(evaluationTime).get("OIS");

			} else deltaSensitivitiesOfCurve = zeroBucketsIR;

		break;

		default:
			throw new IllegalArgumentException("Unknow curve: " + curveIndexName);
		}

		return deltaSensitivitiesOfCurve;
	}




	/**Calculate the sensitivities of the value of a product w.r.t. swap rates given the Libor sensitivities dV/dL
	 * This is the mapping of libor sensitivities dV/dL to (SIMM) model sensitivities dV/dS.
	 *  
	 * @param evaluationTime The time of evaluation
	 * @param dVdL The vector of derivatives dV/dL = dV/dL_0,...,dV/dL_n
	 * @param model The Libor Market Model
	 * @return The derivatives dV/dS 
	 * @throws CalculationException
	 */
	public RandomVariableInterface[] mapLiborToMarketRateSensitivities(double evaluationTime, 
			RandomVariableInterface[] dVdL, /*Libor sensitivities*/
			LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		// the following line will be removed later. Just checking how timeGridAdjustment affects the result
		int timeGridIndicator = 0; if(!isUseTimeGridAdjustment && !onLiborPeriodDiscretization(evaluationTime,model)) timeGridIndicator = 1;

		RandomVariableInterface[] delta = new RandomVariableInterface[dVdL.length-timeGridIndicator];
		RandomVariableInterface[][] dLdS;
		if(this.liborWeightMethod == WeightMode.Stochastic){
			dLdS = getLiborSwapSensitivities(evaluationTime, model);
		} else dLdS = getLiborSwapSensitivities(0.0, model);
		// Calculate Sensitivities wrt Swaps
		// return multiply(dVdL,dLdS);
		for(int swapIndex = 0; swapIndex<dVdL.length-timeGridIndicator; swapIndex++){
			RandomVariableInterface dVdS  =new RandomVariable(0.0);
			RandomVariableInterface factor;
			for(int liborIndex=0;liborIndex<dVdL.length-timeGridIndicator;liborIndex++){
				factor = dLdS[liborIndex][swapIndex]==null ?  new RandomVariable(0.0) : dLdS[liborIndex][swapIndex];
				dVdS = dVdS.addProduct(dVdL[liborIndex+timeGridIndicator], factor);
			}
			delta[swapIndex]=dVdS;
		}
		return delta;
	}


	/**Performs rebucketing of sensitivities to the SIMM buckets by linear interpolation (Source: Master Thesis of Jamal Issa, modified).
	 * 
	 * @param sensitivities The sensitivities wrt swap rates dV/dS
	 * @param riskClass The risk class
	 * @param riskFactorDays The number of days corresponding to the sensitivities
	 * @return The sensitivities on the SIMM maturity buckets
	 */
	public static RandomVariableInterface[] mapSensitivitiesOnBuckets(RandomVariableInterface[] sensitivities, String riskClass, int[] riskFactorDays, LIBORModelMonteCarloSimulationInterface model){
		//rebucketing to SIMM structure(buckets: 2w, 1m, 3m, 6m, 1y, 2y, 3y, 5y, 10y, 15y, 20y, 30y)	
		int[] riskFactorsSIMM = riskClass=="InterestRate" ? new int[] {14, 30, 90, 180, 365, 730, 1095, 1825, 3650, 5475, 7300, 10950} : /*Credit*/ new int[] {365, 730, 1095, 1825, 3650};	
		RandomVariableInterface[] deltaSIMM = new RandomVariableInterface[riskFactorsSIMM.length];
		for(int i = 0;i<deltaSIMM.length;i++) deltaSIMM[i] = new RandomVariable(0.0);

		if(riskFactorDays==null) riskFactorDays = riskFactorDaysLibor(sensitivities, model);
		int counter = 0;
		for(int simmFactor =0; simmFactor<riskFactorsSIMM.length;simmFactor++){
			for(int i = counter; i<sensitivities.length; i++){


				if(riskFactorDays[i] < riskFactorsSIMM[0]){
					deltaSIMM[0] = deltaSIMM[0].add(sensitivities[i]);
					counter++;
				}
				else{
					if(riskFactorDays[i] >= riskFactorsSIMM[riskFactorsSIMM.length-1]){
						deltaSIMM[deltaSIMM.length-1] = deltaSIMM[deltaSIMM.length-1].add(sensitivities[i]);
					}

					else{
						if(riskFactorDays[i] >= riskFactorsSIMM[simmFactor] && riskFactorDays[i] < riskFactorsSIMM[simmFactor+1]){

							deltaSIMM[simmFactor] = deltaSIMM[simmFactor].addProduct(sensitivities[i],((double)(riskFactorsSIMM[simmFactor+1] - riskFactorDays[i]) / (riskFactorsSIMM[simmFactor+1]-riskFactorsSIMM[simmFactor])));
							deltaSIMM[simmFactor+1] = deltaSIMM[simmFactor+1].addProduct(sensitivities[i],((double)(riskFactorDays[i]-riskFactorsSIMM[simmFactor]) / (riskFactorsSIMM[simmFactor+1]-riskFactorsSIMM[simmFactor])));
							counter++;
						}							
						else{
							break;
						}
					}

				}
			}

		}

		return deltaSIMM;		

	}


	/**Calculates dL/dS: The risk weights used to apply Libor Sensitivities to SIMM: dV/dS = dV/dL * dL/dS.
	 * 
	 * @param evaluationTime The time at which the sensitivity is calculated
	 * @param model The Libor market model
	 * @return The matrix dL/dS 
	 * @throws CalculationException
	 */
	private RandomVariableInterface[][] getLiborSwapSensitivities(double evaluationTime, LIBORModelMonteCarloSimulationInterface model) throws CalculationException{

		if(riskWeightMap.containsKey(evaluationTime)) return riskWeightMap.get(evaluationTime);

		RandomVariableInterface[][] dLdS=null;
		double liborPeriodLength = model.getLiborPeriodDiscretization().getTimeStep(0);

		// Get index of first Libor starting >= evaluationTime
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		int numberOfRemainingLibors = model.getNumberOfLibors()-nextLiborIndex;
		dLdS = new RandomVariableInterface [numberOfRemainingLibors][numberOfRemainingLibors];

		// Calculate dLdS directly  
		dLdS[0][0]=new RandomVariable(1.0);
		double discountTime = evaluationTime+liborPeriodLength;
		RandomVariableInterface sumDf = model.getForwardBondOIS(discountTime, evaluationTime);
		for(int liborIndex = 1; liborIndex<dLdS.length;liborIndex++){
			discountTime +=liborPeriodLength;
			RandomVariableInterface denominator = model.getForwardBondOIS(discountTime, evaluationTime);
			dLdS[liborIndex][liborIndex-1]=sumDf.div(denominator).mult(-1.0);
			sumDf = sumDf.add(denominator);
			dLdS[liborIndex][liborIndex] = sumDf.div(denominator);

		}

		riskWeightMap.put(evaluationTime, dLdS);		
		return dLdS;
	}


	/**Calculates dPdS in a single curve context. Used for calculating sensis with respect to discount curve.
	 * 
	 * @param evaluationTime The time at which the initial margin is calculated
	 * @param model The Libor market model
	 * @return The sensitivity of the discount curve (bonds) wrt to swap rates of the same curve.
	 * @throws CalculationException 
	 */
	public static RandomVariableInterface[][] getBondSwapSensitivity(double evaluationTime, 
			LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		int numberOfBonds = getNumberOfRemainingLibors(evaluationTime, model);
		double timeStep = model.getLiborPeriodDiscretization().getTimeStep(0);
		double bondTime = evaluationTime;
		RandomVariableInterface sum= new RandomVariable(0.0);
		RandomVariableInterface[][] dSdP = new RandomVariableInterface[numberOfBonds][numberOfBonds];

		for(int swapIndex=0;swapIndex<dSdP.length;swapIndex++){
			bondTime += timeStep;
			RandomVariableInterface bondOIS = model.getForwardBondOIS(bondTime /*T*/,evaluationTime /*t*/); //P^OIS(T;t)
			sum = sum.addProduct(bondOIS,timeStep);
			for(int bondIndex=0;bondIndex<dSdP.length;bondIndex++){
				if(swapIndex<bondIndex) dSdP[swapIndex][bondIndex] = new RandomVariable(0.0);
				else if(swapIndex==bondIndex) dSdP[swapIndex][bondIndex] = sum.mult(-1.0).sub(timeStep).addProduct(bondOIS,timeStep).div(sum.squared());
				else dSdP[swapIndex][bondIndex] = bondOIS.sub(1.0).mult(timeStep).div(sum.squared());    			    	
			}
		} 

		return getPseudoInverse(dSdP, model.getNumberOfPaths()); // PseudoInverse == Inverse for n x n matrix.
	}


	/**Since dV/dL is wrt the incorrect Libor times this function provides a matrix dL/dL to be multiplied with dV/dL in order to 
	 * have the correct libor times starting at evaluationTime. 
	 * @param evaluationTime The time at which the adjustment should be calculated.
	 * @param model The Libor market model
	 * @return Pseudo Inverse of derivative band matrix; Identity matrix in case of evaluationTime on LiborPeriodDiscretization; 
	 * @throws CalculationException
	 */
	public static RandomVariableInterface[][] getLiborTimeGridAdjustment(double evaluationTime, LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		int numberOfRemainingLibors = getNumberOfRemainingLibors(evaluationTime,model);

		// If evaluationTime lies on Libor Time Grid - return identity matrix
		if (onLiborPeriodDiscretization(evaluationTime,model)) {
			RandomVariableInterface[][] dLdL = new RandomVariableInterface[numberOfRemainingLibors][numberOfRemainingLibors];
			for(int i=0;i<dLdL.length;i++) dLdL[i][i]=new RandomVariable(1.0);
			return dLdL;
		}

		// Calculate dLdL. It is a (n-1)x n Matrix!
		RandomVariableInterface[][] dLdL = new RandomVariableInterface[numberOfRemainingLibors][numberOfRemainingLibors+1];
		double swapTenorLength = model.getLiborPeriodDiscretization().getTimeStep(0); // Model must have same tenor as swap!
		double timeOfFirstLiborPriorToEval = getPreviousLiborTime(evaluationTime,model);
		int timeIndexAtEvaluationTime = model.getTimeDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		int timeIndexAtFirstLiborPriorToEval = model.getTimeDiscretization().getTimeIndexNearestGreaterOrEqual(timeOfFirstLiborPriorToEval);

		for(int liborIndex = 0; liborIndex <numberOfRemainingLibors; liborIndex++){
			double liborTime = evaluationTime+liborIndex*swapTenorLength; // t+j*\Delta T
			int    previousLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestLessOrEqual(liborTime);
			double previousLiborTime = model.getLiborPeriodDiscretization().getTime(previousLiborIndex);
			double firstNextLiborTime = model.getLiborPeriodDiscretization().getTime(previousLiborIndex+1);
			double secondNextLiborTime = model.getLiborPeriodDiscretization().getTime(previousLiborIndex+2);
			double factor1 = (secondNextLiborTime-(liborTime+swapTenorLength))/(secondNextLiborTime-firstNextLiborTime);
			double factor2 = (liborTime-previousLiborTime)/(firstNextLiborTime-previousLiborTime);
			int    timeIndex = liborIndex==0 ? timeIndexAtFirstLiborPriorToEval : timeIndexAtEvaluationTime;
			// Get Libors
			RandomVariableInterface previousLibor = model.getLIBOR(timeIndex, previousLiborIndex);     
			RandomVariableInterface nextLibor     = model.getLIBOR(timeIndex, previousLiborIndex + 1); 
			RandomVariableInterface logInterpol = nextLibor.mult(secondNextLiborTime-firstNextLiborTime).add(1.0).log().mult(-factor1);
			logInterpol = logInterpol.add(previousLibor.mult(firstNextLiborTime-previousLiborTime).add(1.0).log().mult(-factor2)).exp();
			// Set derivatives
			dLdL[liborIndex][liborIndex]   = nextLibor.mult(secondNextLiborTime-firstNextLiborTime).add(1.0).mult(logInterpol).mult(1-factor2);// dLdL_i-1
			dLdL[liborIndex][liborIndex+1] = previousLibor.mult(firstNextLiborTime-previousLiborTime).add(1.0).mult(logInterpol).mult(1-factor1);
		}

		// dLdL is (n-1) x n matrix. Get PseudoInverse for all paths and then put it back together as RV
		return getPseudoInverse(dLdL, model.getNumberOfPaths());
	}

	public static int[] riskFactorDaysLibor(RandomVariableInterface[] sensis, LIBORModelMonteCarloSimulationInterface model){

		int[] riskFactorDays = new int[sensis.length];
		// act/365 as default daycount convention
		for(int i=0;i<sensis.length;i++) riskFactorDays[i] = (int)Math.round(365 * model.getLiborPeriodDiscretization().getTime(i+1));	

		return riskFactorDays;
	}



	public static boolean onLiborPeriodDiscretization(double evaluationTime, LIBORModelMonteCarloSimulationInterface model){
		return (evaluationTime == getNextLiborTime(evaluationTime,model));
	}

	public static int getNumberOfRemainingLibors(double evaluationTime, LIBORModelMonteCarloSimulationInterface model){
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		return model.getNumberOfLibors()-nextLiborIndex;
	}

	public static double getNextLiborTime(double evaluationTime, LIBORModelMonteCarloSimulationInterface model){
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		return model.getLiborPeriodDiscretization().getTime(nextLiborIndex);
	}

	public static double getPreviousLiborTime(double evaluationTime, LIBORModelMonteCarloSimulationInterface model){
		if(evaluationTime==0) return 0.0;
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		return model.getLiborPeriodDiscretization().getTime(nextLiborIndex-1);
	}


	public SensitivityMode getSensitivityMode(){
		return this.sensitivityMode;
	}

	public WeightMode getWeightMode(){
		return this.liborWeightMethod;
	}

	public void clearRiskWeights(){
		if(riskWeightMap!=null) riskWeightMap.clear();
	}

	public void setWeightMode(WeightMode mode){
		this.liborWeightMethod = mode;
	}

	public void setSensitivityMode(SensitivityMode mode){
		this.sensitivityMode = mode;
	}
	//----------------------------------------------------------------------------------------------------------------------------------
	// Some auxiliary functions
	//----------------------------------------------------------------------------------------------------------------------------------

	/**Calculate Pseudo Inverse of matrix of type RandomVariableInterface[][]
	 * 
	 * @param matrix The matrix for which the pseudo inverse is calculated
	 * @return The pseudo inverse of the matrix
	 */
	public static RandomVariableInterface[][] getPseudoInverse(RandomVariableInterface[][] matrix, int numberOfPaths){

		double[][][] inv = new double[matrix[0].length][matrix.length][numberOfPaths];
		double[][] matrixOnPath = new double[matrix.length][matrix[0].length];
		long start = System.currentTimeMillis();
		IntStream.range(0, numberOfPaths).parallel().forEach(pathIndex -> {
			for(int i=0;i<matrixOnPath.length;i++){
				for(int j=0;j<matrixOnPath[0].length;j++){
					matrixOnPath[i][j]=matrix[i][j]==null ? 0 : matrix[i][j].get(pathIndex);
				}
			}
			// Get Pseudo Inverse 
			RealMatrix pseudoInverse = new SingularValueDecomposition(MatrixUtils.createRealMatrix(matrixOnPath)).getSolver().getInverse();
			for(int j=0;j<pseudoInverse.getColumnDimension();j++){
				double[] columnValues = pseudoInverse.getColumn(j);
				for(int i=0;i<pseudoInverse.getRowDimension();i++){
					inv[i][j][pathIndex]= columnValues[i];
				}
			}

		});


		// Wrap to RandomVariableInterface[][]
		RandomVariableInterface[][] pseudoInverse = new RandomVariableInterface[matrix[0].length][matrix.length];
		for(int i=0;i<pseudoInverse.length; i++){
			for(int j=0;j<pseudoInverse[0].length; j++){
				pseudoInverse[i][j] = new RandomVariable(0.0 /*should be evaluationTime*/,inv[i][j]);
			}
		}
		long end= System.currentTimeMillis();
		secondsPseudoInverse = secondsPseudoInverse + ((end-start)/1000.0);
		//			System.out.println("Total time of pseudo inverse: " + secondsPseudoInverse); 
		return pseudoInverse;
	}


	public static RandomVariableInterface[][] multiply(RandomVariableInterface[][] A,RandomVariableInterface[][] B){
		RandomVariableInterface[][] AB = new RandomVariableInterface[A.length][B.length];
		RandomVariableInterface ABproduct;
		for(int i=0;i<A.length;i++){
			for(int j=0; j<B.length; j++){
				AB[i][j] = new RandomVariable(0.0);
				for(int k=0;k<B.length;k++) {
					if(A[i][k]==null || B[k][j]==null) {ABproduct = new RandomVariable(0.0);}
					else {ABproduct = A[i][k].mult(B[k][j]);}
					AB[i][j]=AB[i][j].add(ABproduct);
				}
			}
		}
		return AB;
	}

	public static RandomVariableInterface[] multiply(RandomVariableInterface[] A,RandomVariableInterface[][] B){
		RandomVariableInterface[] AB = new RandomVariableInterface[B[0].length];
		RandomVariableInterface ABproduct;
		for(int i=0;i<B[0].length;i++){
			AB[i] = new RandomVariable(0.0);
			for(int k=0;k<A.length;k++) {
				if(A[k]==null || B[k][i]==null) {ABproduct = new RandomVariable(0.0);}
				else {ABproduct = A[k].mult(B[k][i]);}
				AB[i]=AB[i].add(ABproduct);
			}
		}
		return AB;
	}


	/** Calculate the delta sensitivities of the value of a product w.r.t. the swap rates of LIBOR and OIS curve.
	 * 
	 * @param product The product whose sensitivities are calculated
	 * @param evaluationTime The time of evaluation (initial margin calculation time)
	 * @param model The LIBOR market model
	 * @return A map assigning the swap rate sensitivities on the SIMM Maturity buckets to the curve label (OIS, Libor6m)
	 * @throws CalculationException
	 */
	public Map<String,RandomVariableInterface[]> getMarketRateSensitivities(AbstractSIMMProduct product, double evaluationTime, LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		Map<String, RandomVariableInterface[]> curveSwapRateSensitivityMap = new HashMap<>();

		/*
		 * Calculate Model Sensitivities
		 */
		// Calculate dV/dL
		RandomVariableInterface[] dVdL = product.getValueLiborSensitivities(evaluationTime, model);
		// Calculate dV/dN
		RandomVariableInterface[] dVdN = product.getValueNumeraireSensitivities(evaluationTime, model);
		// Create row-vector (dVdL, dVdN)
		RandomVariableInterface[] modelSensitivities = (RandomVariableInterface[])ArrayUtils.addAll(dVdL, dVdN);//.values().stream().sorted().toArray(RandomVariableInterface[]::new));

		// Calculate Jacobian 
		/* dS_{LIBOR}/dL     dS_{LIBOR}/dN
		 * dS_{OIS}/dL       dS_{OIS}/dN
		 */
		RandomVariableInterface[][] jacobian = getModelToMarketRateJacobianMatrix(evaluationTime, model);
		// Multiply matrices according to Chain Rule
		RandomVariableInterface[] dVdS = multiply(modelSensitivities, jacobian);
		RandomVariableInterface[] dVdSLibor = ArrayUtils.subarray(dVdS, 0, (int)(dVdS.length/2.0));
		RandomVariableInterface[] dVdSOIS = ArrayUtils.subarray(dVdS, (int)(dVdS.length/2.0), dVdS.length);
		// Map sensitivities on SIMM Buckets
		curveSwapRateSensitivityMap.put("Libor6m", mapSensitivitiesOnBuckets(dVdSLibor,"InterestRate",null,model));
		curveSwapRateSensitivityMap.put("OIS", mapSensitivitiesOnBuckets(dVdSOIS,"InterestRate",null,model));
		return curveSwapRateSensitivityMap;
	}


	private RandomVariableInterface[][] getModelToMarketRateJacobianMatrix(double evaluationTime, LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		double periodLength = model.getLiborPeriodDiscretization().getTimeStep(0);
		int numberOfPeriods = getNumberOfRemainingLibors(evaluationTime, model); // The number of full periodLengths until the time horizon of the model as of time "evaluationTime"
		int numberOfLibors = getNextLiborTime(evaluationTime, model) == evaluationTime ? numberOfPeriods : numberOfPeriods+1;
		int timeGridIndicator = evaluationTime == getNextLiborTime(evaluationTime,model) ? 0 : 1;
		int timeIndex = model.getTimeDiscretization().getTimeIndexNearestLessOrEqual(evaluationTime);
		RandomVariableInterface[][] jacobian = new RandomVariableInterface[2*numberOfPeriods][2*numberOfLibors];
		for(int i= 0; i<jacobian.length; i++) Arrays.fill(jacobian[i], new RandomVariable(0.0));

		int lastLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestLessOrEqual(evaluationTime);
		RandomVariableInterface sumBondOIS = new RandomVariable(0.0);
		RandomVariableInterface sumLiborTimesBondOIS = new RandomVariable(0.0);


		RandomVariableInterface expectationNumeraireEval = model.getNumeraire(evaluationTime).pow(-1.0).average();
		DiscountCurveInterface discountCurveLibor = model.getModel().getAnalyticModel().getDiscountCurve("DiscountCurveFromForwardCurveLibor6m)");


		for(int i=0;i<numberOfPeriods; i++){// Libor Swap Rate
			double maturity = evaluationTime+(i+1)*periodLength;
			RandomVariableInterface bondLibor = model.getForwardBondLibor(maturity, evaluationTime);
			RandomVariableInterface expectationNumeraire = model.getNumeraire(maturity).pow(-1.0).average();
			double liborAdjustment = discountCurveLibor.getDiscountFactor(maturity)/discountCurveLibor.getDiscountFactor(evaluationTime);
			RandomVariableInterface bondOIS   = bondLibor.mult(expectationNumeraire.div(expectationNumeraireEval)).div(liborAdjustment);


			//System.out.println(model.getForwardBondOIS(maturity, evaluationTime).getAverage() + "\t" + bondOIS.getAverage());
			sumBondOIS = bondOIS.add(sumBondOIS);
			RandomVariableInterface libor = model.getLIBOR(evaluationTime, evaluationTime+i*periodLength, evaluationTime+(i+1)*periodLength);
			sumLiborTimesBondOIS = libor.mult(bondOIS).add(sumLiborTimesBondOIS);

			// Calculate S_{LIBOR}
			RandomVariableInterface swapRateLibor = sumLiborTimesBondOIS.div(sumBondOIS);
			RandomVariableInterface swapRateOIS   = bondOIS.mult(-1.0).add(1.0).div(sumBondOIS.mult(periodLength));

			Map<Long, RandomVariableInterface> swapRateLiborGradient = ((RandomVariableDifferentiableInterface)swapRateLibor).getGradient();
			Map<Long, RandomVariableInterface> swapRateOISGradient = ((RandomVariableDifferentiableInterface)swapRateOIS).getGradient();

			if(numberOfPeriods!=numberOfLibors){
				double lastLiborTime = model.getLiborPeriodDiscretization().getTime(lastLiborIndex);
				RandomVariableInterface lastLibor = model.getLIBOR(model.getTimeDiscretization().getTimeIndex(lastLiborTime), lastLiborIndex);
				RandomVariableInterface lastNumeraire = model.getNumeraire(lastLiborTime);

				// Set dS_{LIBOR}/dL
				RandomVariableInterface derivative = swapRateLiborGradient.get(((RandomVariableDifferentiableInterface)lastLibor).getID());
				if(derivative!=null) jacobian[i][0]  = derivative;	

				// Set dS_{LIBOR}/dN
				derivative = swapRateLiborGradient.get(((RandomVariableDifferentiableInterface)lastNumeraire).getID());
				if(derivative!=null) jacobian[i][numberOfLibors]  = derivative;	

				// Set dS_{OIS}/dL
				derivative = swapRateOISGradient.get(((RandomVariableDifferentiableInterface)lastLibor).getID());
				if(derivative!=null) jacobian[i+numberOfPeriods][0]  = derivative;

				// Set dS_{LIBOR}/dN
				derivative = swapRateOISGradient.get(((RandomVariableDifferentiableInterface)lastNumeraire).getID());
				if(derivative!=null) jacobian[i+numberOfPeriods][numberOfLibors]  = derivative;
			}


			for(int liborIndex=lastLiborIndex+timeGridIndicator;liborIndex<model.getNumberOfLibors(); liborIndex++){
				RandomVariableInterface liborAtTimeIndex = model.getLIBOR(timeIndex, liborIndex);
				RandomVariableInterface numeraire        = model.getNumeraire(model.getLiborPeriod(liborIndex));

				// Set dS_{LIBOR}/dL
				RandomVariableInterface derivative = swapRateLiborGradient.get(((RandomVariableDifferentiableInterface)liborAtTimeIndex).getID());
				if(derivative!= null) jacobian[i][liborIndex-lastLiborIndex] =  derivative;

				// Set dS_{LIBOR}/dN
				derivative = swapRateLiborGradient.get(((RandomVariableDifferentiableInterface)numeraire).getID());
				if(derivative!=null) jacobian[i][liborIndex-lastLiborIndex+numberOfLibors]  = derivative;	

				// Set dS_{OIS}/dL
				derivative = swapRateOISGradient.get(((RandomVariableDifferentiableInterface)liborAtTimeIndex).getID());
				if(derivative!=null) jacobian[i+numberOfPeriods][liborIndex-lastLiborIndex]  = derivative;

				// Set dS_{LIBOR}/dN
				derivative = swapRateOISGradient.get(((RandomVariableDifferentiableInterface)numeraire).getID());
				if(derivative!=null) jacobian[i+numberOfPeriods][liborIndex-lastLiborIndex+numberOfLibors]  = derivative;

			}
		}
		return getPseudoInverse(jacobian, model.getNumberOfPaths());	
	}


	private RandomVariableInterface[][] getBondJacobian(double time, LIBORModelMonteCarloSimulationInterface model, int numberOfLibors, int numberOfNumeraires) throws CalculationException{
		// Get bond times t+i\Delta t
		double periodLength = model.getLiborPeriodDiscretization().getTimeStep(0);
		int numberOfBonds = getNumberOfRemainingLibors(time, model);	
		int timeIndex = model.getTimeDiscretization().getTimeIndexNearestLessOrEqual(time);

		// Create Jacobi Matrix
		RandomVariableInterface[][] jacobian = new RandomVariableInterface[2*numberOfBonds][numberOfLibors+numberOfNumeraires];
		for(int i= 0; i<jacobian.length; i++) Arrays.fill(jacobian[i], new RandomVariable(0.0));
		int timeGridIndicator = time == getNextLiborTime(time,model) ? 0 : 1;
		int lastLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestLessOrEqual(time);

		for(int i=0; i<jacobian.length; i++){

			RandomVariableInterface bond = i<jacobian.length/2 ? model.getForwardBondLibor(time+(i+1)*periodLength, time) : model.getForwardBondOIS(time+(i+1-jacobian.length/2)*periodLength, time);
			Map<Long, RandomVariableInterface> bondGradient = ((RandomVariableDifferentiableInterface)bond).getGradient();

			// Set dVdL for last libor which is already fixed (if applicable)
			if(timeGridIndicator==1){
				double lastLiborTime = model.getLiborPeriodDiscretization().getTime(lastLiborIndex);
				RandomVariableInterface lastLibor = model.getLIBOR(model.getTimeDiscretization().getTimeIndex(lastLiborTime), lastLiborIndex);
				RandomVariableInterface derivative = bondGradient.get(((RandomVariableDifferentiableInterface)lastLibor).getID());
				if(derivative!=null) jacobian[i][0]  = derivative;	
				if(i>=jacobian.length/2) {
					RandomVariableInterface numeraire = model.getNumeraire(lastLiborIndex);
					RandomVariableInterface derivativeNum = bondGradient.get(((RandomVariableDifferentiableInterface)numeraire).getID());
					if(derivativeNum!=null) jacobian[i][numberOfLibors]  = derivativeNum;
				}
			}

			for(int liborIndex=lastLiborIndex+timeGridIndicator;liborIndex<model.getNumberOfLibors(); liborIndex++){
				RandomVariableInterface liborAtTimeIndex = model.getLIBOR(timeIndex, liborIndex);
				RandomVariableInterface derivative = bondGradient.get(((RandomVariableDifferentiableInterface)liborAtTimeIndex).getID());
				if(derivative!= null) jacobian[i][liborIndex-lastLiborIndex] =  derivative;//.mult(numeraireAtEval);

			}
			int test = (int)(jacobian.length/2.0);
			if(i>=test){ // Calculate dP_{OIS}/dN

				for(int numeraireIndex=lastLiborIndex+timeGridIndicator; numeraireIndex < numberOfNumeraires+lastLiborIndex; numeraireIndex++){
					RandomVariableInterface numeraire = model.getNumeraire(model.getLiborPeriod(numeraireIndex));
					RandomVariableInterface derivativeNum = bondGradient.get(((RandomVariableDifferentiableInterface)numeraire).getID());
					if(derivativeNum!=null) jacobian[i][numeraireIndex+numberOfLibors]= derivativeNum;//dPdN[i-jacobian.length/2][numeraireIndex];
				}
			}

		}
		return getPseudoInverse(jacobian, model.getNumberOfPaths());		
	}


}
