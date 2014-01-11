/**
 * Copyright 2012-2014 Alexey Ragozin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.visualvm.remotevm;

import java.awt.Image;
import java.util.Comparator;

import org.openide.util.ImageUtilities;
import org.openide.util.NbBundle;

import com.sun.tools.visualvm.core.datasource.DataSource;
import com.sun.tools.visualvm.core.datasource.descriptor.DataSourceDescriptor;

/**
 * @author Alexey Ragozin (alexey.ragozin@gmail.com)
 */
public class RemoteSshHostDescriptor extends DataSourceDescriptor<SshHost> {

    private static final Image NODE_ICON = ImageUtilities.loadImage(
            "com/sun/tools/visualvm/host/resources/remoteHost.png", true);   // NOI18N

    /**
     * Creates new instance of RemoteHostDescriptor for a given host.
     * 
     * @param host Host for which to create the descriptor.
     */
    public RemoteSshHostDescriptor(SshHost host) {
        super(host, resolveName(host, host.getHostName()), NbBundle.getMessage(
              RemoteSshHostDescriptor.class, "DESCR_Remote"), NODE_ICON, // NOI18N
              resolvePosition(host, POSITION_AT_THE_END, true), EXPAND_ON_FIRST_CHILD);

    }

    /**
     * Sets a custom comparator for sorting DataSources within a Host.
     * Use setChildrenComparator(null) to restore the default sorting.
     *
     * @param newComparator comparator for sorting DataSources within a Host
     *
     * @since VisualVM 1.3
     */
    public void setChildrenComparator(Comparator<DataSource> newComparator) {
        super.setChildrenComparator(newComparator);
    }

    public boolean supportsRename() {
        return true;
    }

    public boolean providesProperties() {
        return true;
    }
    
}
