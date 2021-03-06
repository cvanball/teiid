/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.jboss;

import java.util.Locale;
import java.util.ResourceBundle;

import org.teiid.core.BundleUtil;


public class IntegrationPlugin {
    private static final String PLUGIN_ID = "org.teiid.jboss" ; //$NON-NLS-1$
    static final String BUNDLE_NAME = PLUGIN_ID + ".i18n"; //$NON-NLS-1$
    public static final BundleUtil Util = new BundleUtil(PLUGIN_ID,BUNDLE_NAME,ResourceBundle.getBundle(BUNDLE_NAME));
    
    public static ResourceBundle getResourceBundle(Locale locale) {
        if (locale == null) {
            locale = Locale.getDefault();
        }
        return ResourceBundle.getBundle(IntegrationPlugin.BUNDLE_NAME, locale);
    }
    
    public static enum Event implements BundleUtil.Event {
    	TEIID50001,
    	TEIID50002,
    	TEIID50003,
    	TEIID50005,
    	TEIID50006,
    	TEIID50007, // failed to load module
    	TEIID50008,
    	TEIID50009,
    	TEIID50010,
    	TEIID50011,
    	TEIID50012, // socket enabled
    	TEIID50013, // Wrong socket protocol
    	TEIID50016, // invalid vdb file
    	TEIID50017, // vdb.xml parse exception
    	TEIID50018, // failed VDB dependency processing
    	TEIID50019, // redeploying VDB
    	TEIID50021, // vdb defined translator not found
    	TEIID50024, // failed metadata load
    	TEIID50025, // VDB deployed
    	TEIID50026, // VDB undeployed
    	TEIID50029, // dynamic metadata loaded
    	TEIID50030,
    	TEIID50035, // translator not found
    	TEIID50036,
    	TEIID50037, // odbc enabled
    	TEIID50038, // embedded enabled
    	TEIID50039, // socket_disabled
    	TEIID50040, // odbc_disabled
    	TEIID50041, // embedded disabled
    	TEIID50043,
    	TEIID50044, // vdb save failed
    	TEIID50047,
    	TEIID50048,
    	TEIID50049,
    	TEIID50054,
    	TEIID50055,
    	TEIID50056,
    	TEIID50057,
    	TEIID50069,
    	TEIID50070,
    	TEIID50071,
    	TEIID50072,
    	TEIID50074,
    	TEIID50075,
    	TEIID50076,
    	TEIID50077,
    	TEIID50078,
    	TEIID50088,
    	TEIID50089, 
    	TEIID50090, //Missing context
    	TEIID50091, // rest different # of param count
    	TEIID50092, // rest procedure execution
    	TEIID50093,
    	TEIID50094,
    	TEIID50095,
    	TEIID50096,
    	TEIID50097,
    	TEIID50098
    }
}
