/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.hadoop.yarn.util.resource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.Resource;

/**
 * A {@link ResourceCalculator} which uses the concept of  
 * <em>dominant resource</em> to compare multi-dimensional resources.
 *
 * Essentially the idea is that the in a multi-resource environment, 
 * the resource allocation should be determined by the dominant share 
 * of an entity (user or queue), which is the maximum share that the 
 * entity has been allocated of any resource. 
 * 
 * In a nutshell, it seeks to maximize the minimum dominant share across 
 * all entities. 
 * 
 * For example, if user A runs CPU-heavy tasks and user B runs
 * memory-heavy tasks, it attempts to equalize CPU share of user A 
 * with Memory-share of user B. 
 * 
 * In the single resource case, it reduces to max-min fairness for that resource.
 * 
 * See the Dominant Resource Fairness paper for more details:
 * www.cs.berkeley.edu/~matei/papers/2011/nsdi_drf.pdf
 */
@Private
@Unstable
public class DominantResourceCalculator extends ResourceCalculator {
  private static final Log LOG =
      LogFactory.getLog(DominantResourceCalculator.class);

  @Override
  public int compare(Resource clusterResource, Resource lhs, Resource rhs,
      boolean singleType) {
    
    if (lhs.equals(rhs)) {
      return 0;
    }
    
    if (isInvalidDivisor(clusterResource)) {
      if ((lhs.getMemorySize() < rhs.getMemorySize() &&
          lhs.getVirtualCores() > rhs.getVirtualCores()) ||
          (lhs.getMemorySize() > rhs.getMemorySize() &&
          lhs.getVirtualCores() < rhs.getVirtualCores())) {
        return 0;
      } else if (lhs.getMemorySize() > rhs.getMemorySize()
          || lhs.getVirtualCores() > rhs.getVirtualCores()) {
        return 1;
      } else if (lhs.getMemorySize() < rhs.getMemorySize()
          || lhs.getVirtualCores() < rhs.getVirtualCores()) {
        return -1;
      }
    }

    float l = getResourceAsValue(clusterResource, lhs, true);
    float r = getResourceAsValue(clusterResource, rhs, true);
    
    if (l < r) {
      return -1;
    } else if (l > r) {
      return 1;
    } else if (!singleType) {
      l = getResourceAsValue(clusterResource, lhs, false);
      r = getResourceAsValue(clusterResource, rhs, false);
      if (l < r) {
        return -1;
      } else if (l > r) {
        return 1;
      }
    }
    
    return 0;
  }

  /**
   * Use 'dominant' for now since we only have 2 resources - gives us a slight
   * performance boost.
   * 
   * Once we add more resources, we'll need a more complicated (and slightly
   * less performant algorithm).
   */
  protected float getResourceAsValue(
      Resource clusterResource, Resource resource, boolean dominant) {
    // Just use 'dominant' resource
    return (dominant) ?
        Math.max(
            (float)resource.getMemorySize() / clusterResource.getMemorySize(),
            (float)resource.getVirtualCores() / clusterResource.getVirtualCores()
            ) 
        :
          Math.min(
              (float)resource.getMemorySize() / clusterResource.getMemorySize(),
              (float)resource.getVirtualCores() / clusterResource.getVirtualCores()
              ); 
  }
  
  @Override
  public long computeAvailableContainers(Resource available, Resource required) {
    return Math.min(
        available.getMemorySize() / required.getMemorySize(),
        available.getVirtualCores() / required.getVirtualCores());
  }

  @Override
  public float divide(Resource clusterResource, 
      Resource numerator, Resource denominator) {
    return 
        getResourceAsValue(clusterResource, numerator, true) / 
        getResourceAsValue(clusterResource, denominator, true);
  }
  
  @Override
  public boolean isInvalidDivisor(Resource r) {
    if (r.getMemorySize() == 0.0f || r.getVirtualCores() == 0.0f) {
      return true;
    }
    return false;
  }

  @Override
  public float ratio(Resource a, Resource b) {
    return Math.max(
        (float)a.getMemorySize()/b.getMemorySize(),
        (float)a.getVirtualCores()/b.getVirtualCores()
        );
  }

  @Override
  public Resource divideAndCeil(Resource numerator, int denominator) {
    return Resources.createResource(
        divideAndCeil(numerator.getMemorySize(), denominator),
        divideAndCeil(numerator.getVirtualCores(), denominator)
        );
  }

  @Override
  public Resource divideAndCeil(Resource numerator, float denominator) {
    return Resources.createResource(
        divideAndCeil(numerator.getMemorySize(), denominator),
        divideAndCeil(numerator.getVirtualCores(), denominator)
        );
  }

  @Override
  public Resource normalize(Resource r, Resource minimumResource,
                            Resource maximumResource, Resource stepFactor) {
    if (stepFactor.getMemorySize() == 0 || stepFactor.getVirtualCores() == 0) {
      Resource step = Resources.clone(stepFactor);
      if (stepFactor.getMemorySize() == 0) {
        LOG.error("Memory cannot be allocated in increments of zero. Assuming "
            + minimumResource.getMemorySize() + "MB increment size. "
            + "Please ensure the scheduler configuration is correct.");
        step.setMemorySize(minimumResource.getMemorySize());
      }

      if (stepFactor.getVirtualCores() == 0) {
        LOG.error("VCore cannot be allocated in increments of zero. Assuming "
            + minimumResource.getVirtualCores() + "VCores increment size. "
            + "Please ensure the scheduler configuration is correct.");
        step.setVirtualCores(minimumResource.getVirtualCores());
      }

      stepFactor = step;
    }

    long normalizedMemory = Math.min(
      roundUp(
        Math.max(r.getMemorySize(), minimumResource.getMemorySize()),
        stepFactor.getMemorySize()),
      maximumResource.getMemorySize());
    int normalizedCores = Math.min(
      roundUp(
        Math.max(r.getVirtualCores(), minimumResource.getVirtualCores()),
        stepFactor.getVirtualCores()),
      maximumResource.getVirtualCores());
    return Resources.createResource(normalizedMemory,
      normalizedCores);
  }

  @Override
  public Resource roundUp(Resource r, Resource stepFactor) {
    return Resources.createResource(
        roundUp(r.getMemorySize(), stepFactor.getMemorySize()),
        roundUp(r.getVirtualCores(), stepFactor.getVirtualCores())
        );
  }

  @Override
  public Resource roundDown(Resource r, Resource stepFactor) {
    return Resources.createResource(
        roundDown(r.getMemorySize(), stepFactor.getMemorySize()),
        roundDown(r.getVirtualCores(), stepFactor.getVirtualCores())
        );
  }

  @Override
  public Resource multiplyAndNormalizeUp(Resource r, double by,
      Resource stepFactor) {
    return Resources.createResource(
        roundUp((long) Math.ceil((float) (r.getMemorySize() * by)),
            stepFactor.getMemorySize()),
        roundUp((int) Math.ceil((float) (r.getVirtualCores() * by)),
            stepFactor.getVirtualCores()));
  }

  @Override
  public Resource multiplyAndNormalizeDown(Resource r, double by,
      Resource stepFactor) {
    return Resources.createResource(
        roundDown((long) (r.getMemorySize() * by), stepFactor.getMemorySize()),
        roundDown((int) (r.getVirtualCores() * by),
            stepFactor.getVirtualCores()));
  }

  @Override
  public boolean fitsIn(Resource cluster,
      Resource smaller, Resource bigger) {
    return smaller.getMemorySize() <= bigger.getMemorySize()
        && smaller.getVirtualCores() <= bigger.getVirtualCores();
  }

  @Override
  public boolean isAnyMajorResourceZero(Resource resource) {
    return resource.getMemorySize() == 0f || resource.getVirtualCores() == 0;
  }
}
