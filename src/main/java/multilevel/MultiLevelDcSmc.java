package multilevel;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.commons.math3.distribution.BetaDistribution;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;


import bayonet.distributions.Exponential;
import bayonet.distributions.Multinomial;
import bayonet.distributions.Normal;
import bayonet.distributions.Random2RandomGenerator;
import bayonet.math.SpecialFunctions;
import bayonet.rplot.PlotHistogram;
import briefj.BriefLists;
import briefj.OutputManager;
import briefj.collections.Counter;
import briefj.opt.Option;
import briefj.run.Results;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;



public class MultiLevelDcSmc
{
  private final MultiLevelDataset dataset;
  private final int nParticles;
  private final OutputManager output = new OutputManager();
  private final MultiLevelDcSmcOptions options;
  
  public static class MultiLevelDcSmcOptions
  {
    @Option public int nParticles = 1000;
    @Option public int levelCutOffForOutput = Integer.MAX_VALUE;
    @Option public double variancePriorRate = 10.0;
    @Option public boolean useTransform = false;
  }
  
  public void sample(Random rand)
  {
    recurse(rand, Lists.newArrayList(dataset.getRoot()));
  }
  
  public MultiLevelDcSmc(MultiLevelDataset dataset, MultiLevelDcSmcOptions options)
  {
    this.dataset = dataset;
    this.nParticles = options.nParticles;
    this.options = options;
    output.setOutputFolder(Results.getResultFolder());
  }

  public static class ParticleApproximation
  {
    public final Particle [] particles;
    public final double [] probabilities;
    private ParticleApproximation(int nParticles)
    {
      this.particles = new Particle[nParticles];
      this.probabilities = new double[nParticles];
    }
    public Particle sample(Random rand)
    {
      int index = Multinomial.sampleMultinomial(rand, probabilities);
      return particles[index];
    }
  }
  
  public static class Particle
  {
    public final BrownianModelCalculator message;
    public final double variance;
    private Particle(BrownianModelCalculator message, double variance)
    {
      this.message = message;
      this.variance = variance;
    }
    public Particle(BrownianModelCalculator leaf)
    {
      this(leaf, Double.NaN);
    }
  }
  
  private ParticleApproximation recurse(Random rand, List<Node> path)
  {
    Node node = BriefLists.last(path);
    Set<Node> children = dataset.getChildren(node);
    
    ParticleApproximation result;
    if (children.isEmpty())
      result = _leafParticleApproximation(rand, node);
    else
    {
      result = new ParticleApproximation(nParticles);
      List<ParticleApproximation> childrenApproximations = Lists.newArrayList();
      
      for (Node child : children)
        childrenApproximations.add(recurse(rand, BriefLists.concat(path,child)));
       
      for (int particleIndex = 0; particleIndex < nParticles; particleIndex++)
      {
        // sample a variance
        double variance = sampleVariance(rand);
        
        // build product sample from children
        double weight = 0.0;
        List<BrownianModelCalculator> sampledCalculators = Lists.newArrayList();
        for (ParticleApproximation childApprox : childrenApproximations)
        {
          sampledCalculators.add(childApprox.particles[particleIndex].message);
          weight += childApprox.probabilities[particleIndex];
        }
        
        // compute weight
        BrownianModelCalculator combined = BrownianModelCalculator.combine(sampledCalculators, variance);
        weight += combined.logLikelihood();
        for (BrownianModelCalculator childCalculator : sampledCalculators)
          weight = weight - childCalculator.logLikelihood();
        weight = weight + varianceRatio(variance);
        
        // add both qts to result
        result.particles[particleIndex] = new Particle(combined, variance);
        result.probabilities[particleIndex] = weight;
      }
    }
    
    // update log norm estimate
    // TODO
    
    // exp normalize
    Multinomial.expNormalize(result.probabilities);
    
    // monitor ESS
    double ess = SMCUtils.ess(result.probabilities);
    double relativeEss = ess/nParticles;
    output.printWrite("ess", "level", node.level, "nodeLabel", node.label, "ess", ess, "relativeEss", relativeEss);
    output.flush();
    
    // perform resampling
    result = resample(rand, result, nParticles);
    
    // report statistics on mean
    if (node.level < options.levelCutOffForOutput)
    {
      int nPlotPoints = 10000;
      DescriptiveStatistics meanStats = new DescriptiveStatistics();
      double [] meanSamples = new double[nPlotPoints];
      double [] varianceSamples = children.isEmpty() ? null : new double[nPlotPoints];
      DescriptiveStatistics varStats = children.isEmpty() ? null : new DescriptiveStatistics();
      for (int i = 0; i < nPlotPoints; i++)
      {
        Particle p = result.particles[i % nParticles];
        double meanPoint = inverseTransform(Normal.generate(rand, p.message.message[0], p.message.messageVariance));
        meanStats.addValue(meanPoint);
        meanSamples[i] = meanPoint;
        if (varianceSamples != null)
        {
          varianceSamples[i] = p.variance;
          varStats.addValue(p.variance);
        }
      }
      File plotsFolder = Results.getFolderInResultFolder("histograms");
      String pathStr = Joiner.on("-").join(path);
      new PlotHistogram(meanSamples).toPDF(new File(plotsFolder, pathStr + "_logisticMean.pdf"));
      output.printWrite("meanStats", "path", pathStr, "meanMean", meanStats.getMean(), "meanSD", meanStats.getStandardDeviation());
      if (varianceSamples != null)
      {
        new PlotHistogram(varianceSamples).toPDF(new File(plotsFolder, pathStr + "_var.pdf"));
        output.printWrite("varStats", "path", pathStr, "varMean", varStats.getMean(), "varSD", varStats.getStandardDeviation());
      }
      output.flush();
    }
    
    
    return result;
  }
  
  private static ParticleApproximation resample(Random rand, ParticleApproximation beforeResampling, int nParticles)
  {
    ParticleApproximation resampledResult = new ParticleApproximation(nParticles);
    Counter<Integer> resampledCounts = SMCUtils.multinomialSampling(rand, beforeResampling.probabilities, nParticles);
    // use the indices to create a new atoms array
    int currentIndex = 0;
    for (int resampledIndex : resampledCounts)
      for (int i = 0; i < resampledCounts.getCount(resampledIndex); i++)
        resampledResult.particles[currentIndex++] = beforeResampling.particles[resampledIndex];
    
    // reset particle logWeights
    double pr1overK = 1.0/nParticles;
    for (int k = 0; k < nParticles; k++)
      resampledResult.probabilities[k] = pr1overK;
    return resampledResult;
  }

  private double varianceRatio(double variance)
  {
    return 0; // assume we are sampling variance from prior for now
  }

  private double sampleVariance(Random rand)
  {
    return Exponential.generate(rand, options.variancePriorRate );
  }

  private ParticleApproximation _leafParticleApproximation(Random rand, Node node)
  {
    ParticleApproximation result = new ParticleApproximation(nParticles);
    Datum observation = dataset.getDatum(node);
    
    // use a beta distributed proposal
    BetaDistribution beta = new BetaDistribution(new Random2RandomGenerator(rand), 1 + observation.numberOfSuccesses, 1 + (observation.numberOfTrials - observation.numberOfSuccesses), BetaDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
    for (int particleIndex = 0; particleIndex < nParticles; particleIndex++)
    {
      double proposed =  beta.sample();
      double logProposed = Math.log(beta.density(proposed));
      double logPi = logBinomialPr(observation.numberOfTrials, observation.numberOfSuccesses, proposed);
      double transformed = transform(proposed);
      double logWeight = logPi - logProposed;
      BrownianModelCalculator leaf = BrownianModelCalculator.observation(new double[]{transformed}, 1, false);
      
      result.particles[particleIndex] = new Particle(leaf);
      result.probabilities[particleIndex] = logWeight;
    }
    
    return result;
  }
  
  private double logBinomialPr(int nTrials, int nSuccesses, double prOfSuccess)
  {
    if (nTrials < 0 || nSuccesses < 0 || nSuccesses > nTrials || prOfSuccess < 0 || prOfSuccess > 1)
      throw new RuntimeException();
    return SpecialFunctions.logBinomial(nTrials, nSuccesses) 
      + nSuccesses             * Math.log(prOfSuccess) 
      + (nTrials - nSuccesses) * Math.log(1.0 - prOfSuccess);
  }

  private double transform(double numberOnSimplex)
  {
    if (options.useTransform )
      return SpecialFunctions.logit(numberOnSimplex);
    else
      return numberOnSimplex;
  }
  
  private double inverseTransform(double realNumber)
  {
    if (options.useTransform)
      return SpecialFunctions.logistic(realNumber);
    else
      return realNumber;
  }
}