/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.emrapi.utils;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Auditable;
import org.openmrs.api.context.Context;
import org.openmrs.module.emrapi.metadata.MetadataPackageConfig;
import org.openmrs.module.emrapi.metadata.MetadataPackagesConfig;
import org.openmrs.module.metadatasharing.ImportConfig;
import org.openmrs.module.metadatasharing.ImportedItem;
import org.openmrs.module.metadatasharing.ImportedPackage;
import org.openmrs.module.metadatasharing.MetadataSharing;
import org.openmrs.module.metadatasharing.api.MetadataSharingService;
import org.openmrs.module.metadatasharing.wrapper.PackageImporter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetadataUtil {
	
	protected static final Log log = LogFactory.getLog(MetadataUtil.class);
	
	public static final String PACKAGES_FILENAME = "packages.xml";
	
	/**
	 * Setup the standard metadata packages
	 * 
	 * @return
	 */
	public static boolean setupStandardMetadata(ClassLoader loader) throws Exception {
        return setupStandardMetadata(loader, PACKAGES_FILENAME);
    }

	public static boolean setupStandardMetadata(ClassLoader loader, String packagesFilePath) throws Exception {
		MetadataPackagesConfig config = getMetadataPackagesForModule(loader, packagesFilePath);
		return loadPackages(config, loader);
	}

    /**
     * Useful for testing, e.g. if you need to load up a specific MDS package
     * @param loader
     * @param namesToLoad something like Reference_Application_Visit_and_Encounter_Types
     * @return
     * @throws Exception
     */
    public static boolean setupSpecificMetadata(ClassLoader loader, String... namesToLoad) throws Exception {
        List<String> namesToKeep = Arrays.asList(namesToLoad);
        MetadataPackagesConfig config = getMetadataPackagesForModule(loader);
        for (Iterator<MetadataPackageConfig> i = config.getPackages().iterator(); i.hasNext(); ) {
            MetadataPackageConfig packageConfig = i.next();
            if (!namesToKeep.contains(packageConfig.getFilenameBase())) {
                i.remove();
            }
        }
        return loadPackages(config, loader);
    }

    private synchronized static boolean loadPackages(MetadataPackagesConfig config, ClassLoader loader) throws IOException {
        boolean anyChanges = false;

        for (MetadataPackageConfig pkg : config.getPackages()) {
            anyChanges |= installMetadataPackageIfNecessary(pkg, loader);
        }

        return anyChanges;
    }

    public static MetadataPackagesConfig getMetadataPackagesForModule(ClassLoader loader) {
		return getMetadataPackagesForModule(loader, PACKAGES_FILENAME);
	}

	public static MetadataPackagesConfig getMetadataPackagesForModule(ClassLoader loader, String packageFilePath) {
		try {
			InputStream stream = loader.getResourceAsStream(packageFilePath);
			String xml = IOUtils.toString(stream);
			MetadataPackagesConfig config = Context.getSerializationService().getDefaultSerializer()
					.deserialize(xml, MetadataPackagesConfig.class);
			return config;
		}
		catch (Exception ex) {
			throw new RuntimeException("Cannot find " + packageFilePath + ", or error deserializing it", ex);
		}
	}
	
	/**
	 * Checks whether the given version of the MDS package has been installed yet, and if not,
	 * install it
	 * 
	 * @param config the metadata package configuration object
	 * @param loader the class loader to use for loading the packages
	 * @return whether any changes were made to the db
	 * @throws IOException
	 */
	private static boolean installMetadataPackageIfNecessary(MetadataPackageConfig config, ClassLoader loader)
	    throws IOException {
        String filename = config.getFilenameBase() + "-" + config.getVersion().toString() + ".zip";
        try {

			Matcher matcher = Pattern.compile("(?:.+/)?\\w+-(\\d+).zip").matcher(filename);
			if (!matcher.matches())
				throw new RuntimeException("Filename must match PackageNameWithNoSpaces-X.zip");
			Integer version = Integer.valueOf(matcher.group(1));
			
			ImportedPackage installed = Context.getService(MetadataSharingService.class).getImportedPackageByGroup(
			    config.getGroupUuid());
			if (installed != null && installed.getVersion() >= version) {
				log.info("Metadata package " + config.getFilenameBase() + " is already installed with version "
				        + installed.getVersion());
				return false;
			}
			
			if (loader.getResource(filename) == null) {
				throw new RuntimeException("Cannot find " + filename + " for group " + config.getGroupUuid());
			}

            log.info("About to import MDS package: " + filename);
            long timer = System.currentTimeMillis();
			PackageImporter metadataImporter = MetadataSharing.getInstance().newPackageImporter();
			metadataImporter.setImportConfig(ImportConfig.valueOf(config.getImportMode()));
            log.info("...loading package: " + filename);
            metadataImporter.loadSerializedPackageStream(loader.getResourceAsStream(filename));
            log.info("...importing package: " + filename);
            metadataImporter.importPackage();
            log.info("Imported " + filename + " in " + (System.currentTimeMillis() - timer) + "ms");
			return true;
		}
		catch (Exception ex) {
			log.error("Failed to install metadata package " + filename, ex);
			return false;
		}
	}

    /**
     * If multiple MDS packages contain different versions of the same item, then loading them is order-dependent, which
     * is bad.
     * Any OpenMRS distribution that uses the #installMetadataPackageIfNecessary functionality in this class should
     * have a one-line unit test that calls this method, basically like:
     * <pre>
     * public class InconsistantMetadataTest extends BaseModuleContextSensitiveTest {
     *     @Test
     *     public void testThatThereAreNoMdsPackagesWithInconsistentVersionsOfTheSameItem() throws Exception {
     *         MetadataUtil.verifyNoMdsPackagesWithInconsistentVersionsOfTheSameItem(getClass().getClassLoader());
     *     }
     * }
     * </pre>
     *
     * @param classLoader
     * @throws Exception
     * @throws IllegalStateException if different versions of the same metadata item are contained in two packages
     */
    public static void verifyNoMdsPackagesWithInconsistentVersionsOfTheSameItem(ClassLoader classLoader) throws Exception, IllegalStateException {
        ItemToDateMap itemToDateMap = new ItemToDateMap();

        MetadataPackagesConfig allConfigs = MetadataUtil.getMetadataPackagesForModule(classLoader);
        for (MetadataPackageConfig config : allConfigs.getPackages()) {
            String filenameBase = config.getFilenameBase();
            String filename = filenameBase + "-" + config.getVersion().toString() + ".zip";
            log.debug("Inspecting " + filename);
            System.out.println("Inspecting " + filename);

            PackageImporter metadataImporter = MetadataSharing.getInstance().newPackageImporter();
            metadataImporter.setImportConfig(ImportConfig.valueOf(config.getImportMode()));
            metadataImporter.loadSerializedPackageStream(classLoader.getResourceAsStream(filename));
            for (int i = 0; i < metadataImporter.getPartsCount(); ++i) {
                Collection<ImportedItem> items = metadataImporter.getImportedItems(i);
                for (ImportedItem item : items) {
                    itemToDateMap.addItem(item, filenameBase);
                    for (ImportedItem related : item.getRelatedItems()) {
                        itemToDateMap.addItem(related, filenameBase);
                    }
                }
            }

            log.debug("Finished. Running total number of distinct items: " + itemToDateMap.size());
            System.out.println("Finished. Running total number of distinct items: " + itemToDateMap.size());
        }

        if (log.isInfoEnabled()) {
            Map<String, Set<String>> repeated = itemToDateMap.repeatedItems();
            if (log.isDebugEnabled()) {
                log.debug("Items that occur in multiple MDS packages:");
                for (Map.Entry<String, Set<String>> e : repeated.entrySet()) {
                    log.debug(e.getKey() + " -> " + e.getValue());
                }
            }
            log.info("Number of distinct items in multiple packages: " + repeated.size());
            log.info("Total number of distinct items: " + itemToDateMap.size());
        }
    }

    static class ItemToDateMap {

        // classname + uuid to last date modified
        Map<String, Date> lastModifiedMap = new HashMap<String, Date>();
        Map<String, Set<String>> itemToPackages = new HashMap<String, Set<String>>();

        public void addItem(ImportedItem item, String filename) {
            String key = getKey(item);
            Date lastModified = getLastModified(item.getIncoming());

            Set<String> belongsToPackages = itemToPackages.get(key);
            if (belongsToPackages == null) {
                belongsToPackages = new TreeSet<String>();
                itemToPackages.put(key, belongsToPackages);
            }
            belongsToPackages.add(filename);

            Date existing = lastModifiedMap.get(key);
            if (existing == null) {
                lastModifiedMap.put(key, lastModified);
            }
            else {
                if (!existing.equals(lastModified)) {
                    throw new IllegalStateException("Found inconsistent versions of " + key + " in " + belongsToPackages);
                }
            }
        }

        private Date getLastModified(Object object) {
            if (object instanceof Auditable) {
                Date dateChanged = ((Auditable) object).getDateChanged();
                if (dateChanged != null) {
                    return dateChanged;
                } else {
                    return ((Auditable) object).getDateCreated();
                }
            }
            else {
                throw new IllegalArgumentException("object must be Auditable");
            }
        }

        private String getKey(ImportedItem item) {
            try {
                return item.getIncomingClassSimpleName() + ":" + PropertyUtils.getProperty(item.getIncoming(), "uuid");
            } catch (Exception e) {
                throw new RuntimeException("Couldn't get UUID from " + item.getIncoming());
            }
        }

        public int size() {
            return lastModifiedMap.size();
        }

        public Map<String, Set<String>> repeatedItems() {
            Map<String, Set<String>> repeatedItems = new TreeMap<String, Set<String>>();
            for (Map.Entry<String, Set<String>> candidate : itemToPackages.entrySet()) {
                if (candidate.getValue().size() > 1) {
                    repeatedItems.put(candidate.getKey(), candidate.getValue());
                }
            }
            return repeatedItems;
        }

    }

}
