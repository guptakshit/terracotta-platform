/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is OffHeap Resource.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */

package org.terracotta.offheapresource;

import java.util.Collection;
import org.terracotta.entity.ServiceProvider;
import org.terracotta.entity.ServiceProviderConfiguration;
import org.terracotta.offheapresource.config.OffheapResourcesType;
import org.terracotta.offheapresource.config.ResourceType;

class OffHeapResourcesConfiguration implements ServiceProviderConfiguration {

  private final OffheapResourcesType xmlConfig;

  OffHeapResourcesConfiguration(OffheapResourcesType xmlConfig) {
    this.xmlConfig = xmlConfig;
  }

  public Collection<ResourceType> getResources() {
    return xmlConfig.getResource();
  }
  
  @Override
  public Class<? extends ServiceProvider> getServiceProviderType() {
    return OffHeapResourcesProvider.class;
  }

}