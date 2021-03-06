/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie.aws.loadbalancers

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.aws.autoscalinggroups.AmazonAutoScalingGroup
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.LOAD_BALANCER
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.orca.OrcaJob
import com.netflix.spinnaker.swabbie.orca.OrchestrationRequest
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.*

@Component
class AmazonLoadBalancerHandler(
  registry: Registry,
  clock: Clock,
  notifiers: List<Notifier>,
  resourceTrackingRepository: ResourceTrackingRepository,
  resourceStateRepository: ResourceStateRepository,
  resourceOwnerResolver: ResourceOwnerResolver<AmazonElasticLoadBalancer>,
  exclusionPolicies: List<ResourceExclusionPolicy>,
  applicationEventPublisher: ApplicationEventPublisher,
  lockingService: Optional<LockingService>,
  retrySupport: RetrySupport,
  private val rules: List<Rule<AmazonElasticLoadBalancer>>,
  private val loadBalancerProvider: ResourceProvider<AmazonElasticLoadBalancer>,
  private val serverGroupProvider: ResourceProvider<AmazonAutoScalingGroup>,
  private val orcaService: OrcaService
) : AbstractResourceTypeHandler<AmazonElasticLoadBalancer>(
  registry,
  clock,
  rules,
  resourceTrackingRepository,
  resourceStateRepository,
  exclusionPolicies,
  resourceOwnerResolver,
  notifiers,
  applicationEventPublisher,
  lockingService,
  retrySupport
) {
  override fun deleteResources(
    markedResources: List<MarkedResource>,
    workConfiguration: WorkConfiguration
  ): ReceiveChannel<MarkedResource> = produce<MarkedResource> {
    markedResources.forEach { markedResource ->
      markedResource.resource.let { resource ->
        if (resource is AmazonElasticLoadBalancer && !workConfiguration.dryRun) {
          //TODO: consider also removing dns records for the ELB
          log.info("This load balancer is about to be deleted {}", markedResource)
          orcaService.orchestrate(
            OrchestrationRequest(
              application = FriggaReflectiveNamer().deriveMoniker(markedResource).app,
              job = listOf(
                OrcaJob(
                  type = "deleteLoadBalancer",
                  context = mutableMapOf(
                    "credentials" to workConfiguration.account.name,
                    "loadBalancerName" to resource.name,
                    "cloudProvider" to resource.cloudProvider,
                    "regions" to listOf(workConfiguration.location)
                  )
                )
              ),
              description = "Cleaning up Load Balancer for ${FriggaReflectiveNamer().deriveMoniker(markedResource).app}"
            )
          )
        }
      }

      //TODO: check to see if the task was successful
      send(markedResource)
    }
  }

  override fun getCandidate(markedResource: MarkedResource,
                            workConfiguration: WorkConfiguration
  ): AmazonElasticLoadBalancer? = loadBalancerProvider.getOne(
    Parameters(
      mapOf(
        "loadBalancerName" to markedResource.name!!,
        "account" to workConfiguration.account.accountId!!,
        "region" to workConfiguration.location
      )
    )
  )

  override fun handles(workConfiguration: WorkConfiguration): Boolean {
    //TODO: handler currently disabled.
//    return workConfiguration.resourceType == LOAD_BALANCER && workConfiguration.cloudProvider == AWS && !rules.isEmpty()
    return false
  }


  override fun getCandidates(workConfiguration: WorkConfiguration): List<AmazonElasticLoadBalancer>? =
    loadBalancerProvider.getAll(
      Parameters(
        mapOf(
          "account" to workConfiguration.account.accountId!!,
          "region" to workConfiguration.location
        )
      )
    ).orEmpty()

  override fun preProcessCandidates(
    candidates: List<AmazonElasticLoadBalancer>,
    workConfiguration: WorkConfiguration
  ): List<AmazonElasticLoadBalancer> {
    //TODO: need to check other references.
    return referenceServerGroups(
      workConfiguration = workConfiguration,
      loadBalancers = candidates
    )
  }

  private fun referenceServerGroups(workConfiguration: WorkConfiguration,
                                    loadBalancers: List<AmazonElasticLoadBalancer>
  ): List<AmazonElasticLoadBalancer> {
    serverGroupProvider.getAll(
      Parameters(
        mapOf(
          "account" to workConfiguration.account.accountId!!,
          "region" to workConfiguration.location
        )
      )
    ).let { serverGroups ->
      if (serverGroups == null || serverGroups.isEmpty()) {
        throw IllegalStateException("Unable to retrieve server groups")
      }

      serverGroups.forEach { serverGroup ->
        loadBalancers.addServerGroupReferences(serverGroup)
      }
    }

    return loadBalancers
  }

  private fun List<AmazonElasticLoadBalancer>.addServerGroupReferences(serverGroup: AmazonAutoScalingGroup)
    = filter {
    (serverGroup.details["loadBalancerNames"] as List<*>).contains(it.name)
  }.map { elb ->
    elb.details["serverGroups"] = elb.details["serverGroups"] ?: mutableListOf<String>()
    (elb.details["serverGroups"] as MutableList<String>).add(serverGroup.name)
  }
}
