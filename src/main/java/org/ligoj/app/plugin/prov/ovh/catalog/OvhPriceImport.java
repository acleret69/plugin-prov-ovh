/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.ovh.catalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.catalog.AbstractUpdateContext;
import org.ligoj.app.plugin.prov.model.AbstractPrice;
import org.ligoj.app.plugin.prov.model.ImportCatalogStatus;
import org.ligoj.app.plugin.prov.model.ProvDatabasePrice;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvSupportPrice;
import org.ligoj.app.plugin.prov.model.ProvSupportType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.plugin.prov.ovh.ProvOvhPluginResource;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.build.Plugin.Engine;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 * 
 * @see https://us.ovhcloud.com/legal/sla/public-cloud for SLA
 * @see https://www.ovhcloud.com/en/support-levels/plans/ for Support prices
 * @see https://api.ovh.com/1.0/cloud/subsidiaryPrice?ovhSubsidiary=US
 */
@Component
@Setter
@Slf4j
public class OvhPriceImport extends AbstractImportCatalogResource {

	@Override
	protected int getWorkload(final ImportCatalogStatus status) {
		return 6; // TODO init + get catalog + vm + db + support+storage
	}

	/**
	 * Configuration key used for URL prices.
	 */
	public static final String CONF_API_PRICES = ProvOvhPluginResource.KEY + ":prices-url";

	/**
	 * Configuration key used for enabled instance type pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_ITYPE = ProvOvhPluginResource.KEY + ":instance-type";

	/**
	 * Configuration key used for enabled database type pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_DTYPE = ProvOvhPluginResource.KEY + ":database-type";
	/**
	 * Configuration key used for enabled database engine pattern names. When value is <code>null</code>, no
	 * restriction.
	 */
	public static final String CONF_ENGINE = ProvOvhPluginResource.KEY + ":database-engine";

	/**
	 * Configuration key used for enabled OS pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_OS = ProvOvhPluginResource.KEY + ":os";

	/**
	 * Configuration key used for enabled regions pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_REGIONS = ProvOvhPluginResource.KEY + ":regions";

	/**
	 * Configuration key used for flavor pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_FLAVOR = ProvOvhPluginResource.KEY + ":flavor";


	/**
	 * <code>
	 * (curl -s https://www.ovhcloud.com/en/public-cloud/prices/; curl -s https://us.ovhcloud.com/public-cloud/prices/) | \
	grep "data-price"  \
	| sed 's/^.*<tr data-price="//g' \
	| sed 's|}"||g' \
	| sed 's|">.*$|"},|' \
	| sed 's/ data-planCode="/,"planCode":"/' \
	| sed 's/ data-regions="/,"regions":"/' \
	| sed 's/ data-price-type="/,"term":"/' \
	| sed 's/&quot;/"/g'\
	| sed 's|\\/|/|g'\
	| sed 's|/month||g'\
	| sed 's|/hour||g'\
	| sed 's|/GB||g'\
	| sed 's|/node||g'\
	| sed 's|\$||g'\
	| sed 's|  | |g'\
	| sed 's| "|"|g'\
	| sed '1 s|^|[|'\
	| sed '$ s|,$|]|' > price.json
	</code>
	 */
	public static final String OVH_PRICES_PATH = "/price.json";

	public static final String OVH_AVAIBILITY_DATABASE_PATH = "/database-availability.json"; // Public:
																								// "/cloud/project/%s/database/availability"

	public static final String OVH_CAPABILITIES_DATABASE_PATH = "/database-capabilities.json"; // Public:
																								// "/cloud/project/%s/database/capabilities"

	/**
	 * Default pricing URL.
	 */
	protected static final String DEFAULT_API_PRICES = "https://da9smdsh48mvy.cloudfront.net/cloud"; // https://ovh.ligoj.io/cloud";

	private static final TypeReference<Map<String, ProvLocation>> MAP_LOCATION = new TypeReference<>() {
		// Nothing to extend
	};

	private static final TypeReference<List<OvhFlavor>> FLAVOR_LIST = new TypeReference<>() {
		// Nothing to extend
	};

	private static final TypeReference<List<OvhAttrInstance>> DATABASE_LIST = new TypeReference<>() {
		// Nothing to extend
	};
	
	private static final TypeReference<List<Map<String,Object>>> DATABASE_LIST2 = new TypeReference<>() {

		// Nothing to extend

		};

	private static final TypeReference<List<OvhDatabaseAvaibility>> DATABASE_AVAIBILITY_LIST = new TypeReference<>() {
		// Nothing to extend OvhDatabaseCapabilities
	};

	private static final TypeReference<OvhDatabaseCapabilities> DATABASE_CAPABILITIES = new TypeReference<>() {
		// Nothing to extend
	};

	/**
	 * Install or update prices.
	 *
	 * @param force When <code>true</code>, all cost attributes are update.
	 * @throws IOException When CSV or XML files cannot be read.
	 */
	public void install(final boolean force) throws IOException {
		final var context = initContext(new UpdateContext(), ProvOvhPluginResource.KEY, force);
		final var node = context.getNode();

		// Get previous data
		nextStep(context, "initialize");
		context.setValidOs(Pattern.compile(configuration.get(CONF_OS, ".*"), Pattern.CASE_INSENSITIVE));
		context.setValidDatabaseType(Pattern.compile(configuration.get(CONF_DTYPE, ".*"), Pattern.CASE_INSENSITIVE));
		context.setValidDatabaseEngine(
				Pattern.compile(configuration.get(CONF_ENGINE, "(mysql|postgresql)"), Pattern.CASE_INSENSITIVE));
		context.setValidInstanceType(Pattern.compile(configuration.get(CONF_ITYPE, ".*"), Pattern.CASE_INSENSITIVE));
		context.setValidRegion(Pattern.compile(configuration.get(CONF_REGIONS, ".*")));

		// Add all regional DC
		final var regionData = toMap("ovh/regions.json", MAP_LOCATION);
		context.getMapRegionById().putAll(regionData);
		context.setInstanceTypes(itRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvInstanceType::getCode, Function.identity())));
		context.setDatabaseTypes(dtRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvDatabaseType::getCode, Function.identity())));
		context.setPriceTerms(iptRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvInstancePriceTerm::getCode, Function.identity())));
		context.setStorageTypes(stRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvStorageType::getCode, Function.identity())));
		context.setPreviousStorage(spRepository.findAllBy("type.node", node).stream()
				.collect(Collectors.toMap(ProvStoragePrice::getCode, Function.identity())));
		context.setSupportTypes(st2Repository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvSupportType::getName, Function.identity())));
		context.setPreviousSupport(sp2Repository.findAllBy("type.node", node).stream()
				.collect(Collectors.toMap(ProvSupportPrice::getCode, Function.identity())));
		context.setRegions(locationRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.filter(r -> isEnabledRegion(context, r))
				.collect(Collectors.toMap(INamableBean::getName, Function.identity())));
		context.setPrevious(ipRepository.findAllBy("term.node", node).stream()
				.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity())));

		// Fetch the remote prices stream and build the prices object
		nextStep(context, "retrieve-catalog");

		// Instance(VM)
		var hourlyTerm = installPriceTerm(context, "consumption", 0);
		var monthlyTerm = installPriceTerm(context, "monthly.postpaid", 1);
		final var prices = getPrices();

		installInstancePrices(context, prices, hourlyTerm, monthlyTerm);
		installDatabasePrices(context, hourlyTerm, monthlyTerm, prices);
		installStoragePrices(context, prices);
//		installSupportPrices(context);
	}

	private void installSupportPrices(final UpdateContext context) throws IOException {
		nextStep(context, "install-support");
		// Install previous types
		installSupportTypes(context);

		// Fetch previous prices
		final var previous = sp2Repository.findAllBy("type.node", context.getNode()).stream()
				.collect(Collectors.toMap(AbstractPrice::getCode, Function.identity()));

		// Complete the set
		csvForBean.toBean(ProvSupportPrice.class, "csv/ovh-prov-support-price.csv").forEach(t -> {
			final var entity = previous.computeIfAbsent(t.getCode(), n -> t);
			// Merge the support type details
			final var price = copyAsNeeded(context, entity, s -> {
				s.setLimit(t.getLimit());
				s.setMin(t.getMin());
				s.setRate(t.getRate());
			});
			saveAsNeeded(context, price, t.getCost(), sp2Repository);
		});
		nextStep(context, "support", null, 1);
	}

	// Instal instance prices
	private void installInstancePrices(final UpdateContext context, final OvhAllPrices prices,
			final ProvInstancePriceTerm hourlyTerm, ProvInstancePriceTerm monthlyTerm) throws IOException {

//		// For each price/region/OS/software
//		// Install term, type and price
		nextStep(context, "install-vm");
		final var instances = prices.getInstances();
		instances.stream().filter(i -> isEnabledRegion(context, i.getRegion().toLowerCase()))
				.forEach(i -> installInstancePrice(context, i, hourlyTerm, monthlyTerm));
	}

	// Instal database prices
	private void installDatabasePrices(final UpdateContext context, final ProvInstancePriceTerm hourlyTerm,
			final ProvInstancePriceTerm monthlyTerm , final OvhAllPrices prices) throws IOException {
		// Database
		nextStep(context, "install-database");
		final var node = context.getNode();
		context.setPreviousDatabase(dpRepository.findAllBy("term.node", node).stream()
				.collect(Collectors.toMap(ProvDatabasePrice::getCode, Function.identity())));
		
		prices.getDatabases().stream()
				.filter(p ->  isEnabledEngine(context, p.getEngine()) && isEnabledDatabaseType(context, p.getFlavor()))
				.forEach((p) -> { 
						p.getPlanCode().replace("databases.", "").replace(".hour.consumption", "");
						installDatabasePrices(context, hourlyTerm, monthlyTerm,p);
					}
				);
	}

	private void installDatabasePrices(final UpdateContext context, final ProvInstancePriceTerm hourlyTerm,
			final ProvInstancePriceTerm monthlyTerm, final OvhAttrInstance p) {
		final var engine = p.getEngine();
		final var regionGroup = p.getRegion().toLowerCase();
		context.getUsedRegions().stream()
				.forEach(regionName -> {
					final var region = context.getRegions().get(regionName);
					final var codeType = "%s/%s".formatted(p.getPlan(), p.getFlavor());
					final var codePricePlan = "%s-%s-%s".formatted(p.getEngine(), p.getPlan(), p.getFlavor());
					var type = installDatabaseType(context, codeType, p.getFlavor(), p);

					// Install hourly based price
					installDatabasePrice(context, hourlyTerm, codePricePlan, type, p.getHourlyCost(),
							context.getHoursMonth(), engine, region);
					// Install monthly based price
					installDatabasePrice(context, monthlyTerm, codePricePlan, type, p.getMonthlyCost(), 1,
							engine, region);
				});
	}

	private void installDatabasePrice(final UpdateContext context, final ProvInstancePriceTerm term,
			final String codePricePlan, final ProvDatabaseType type, final Double costPeriod, double proRata,
			final String engine, final ProvLocation region) {
		if (costPeriod != null) {
			// Price is available for this term
			installDatabasePrice(context, term, term.getCode() + "/" + codePricePlan, type, costPeriod * proRata,
					engine, region);
		}
	}

	private void installSupportTypes(final UpdateContext context) throws IOException {
		// Fetch previous prices
		final var previous = st2Repository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity()));

		// Complete the set
		csvForBean.toBean(ProvSupportType.class, "csv/ovh-prov-support-type.csv").forEach(t -> {
			final var entity = previous.computeIfAbsent(t.getCode(), n -> t);
			// Merge the support type details
			copyAsNeeded(context, entity, t2 -> {
				t2.setName(t.getCode());
				t2.setDescription(t.getDescription());
				t2.setAccessApi(t.getAccessApi());
				t2.setAccessChat(t.getAccessChat());
				t2.setAccessEmail(t.getAccessEmail());
				t2.setAccessPhone(t.getAccessPhone());
				t2.setSlaStartTime(t.getSlaStartTime());
				t2.setSlaEndTime(t.getSlaEndTime());
				t2.setDescription(t.getDescription());

				t2.setSlaBusinessCriticalSystemDown(t.getSlaBusinessCriticalSystemDown());
				t2.setSlaGeneralGuidance(t.getSlaGeneralGuidance());
				t2.setSlaProductionSystemDown(t.getSlaProductionSystemDown());
				t2.setSlaProductionSystemImpaired(t.getSlaProductionSystemImpaired());
				t2.setSlaSystemImpaired(t.getSlaSystemImpaired());
				t2.setSlaWeekEnd(t.isSlaWeekEnd());

				t2.setCommitment(t.getCommitment());
				t2.setSeats(t.getSeats());
				t2.setLevel(t.getLevel());
			}, st2Repository);
		});
	}

	private String getApiPriceUrl(final String service, String templatePath) {
		return configuration.get(CONF_API_PRICES, DEFAULT_API_PRICES) + String.format(templatePath, service);
	}

	private String getPricesUrl() {
		return getApiPriceUrl("-public-", OVH_PRICES_PATH);
	}

	private <C> C getResource(final Supplier<String> supplierUrl, final Class<C> resourceType,
			final TypeReference<C> typeReference) throws IOException {
		try (var curl = new CurlProcessor()) {
			final var rawJson = StringUtils.defaultString(curl.get(supplierUrl.get()), "{}");
			if (typeReference == null) {
				return objectMapper.readValue(rawJson, resourceType);
			}
			return objectMapper.readValue(rawJson, typeReference);
		}
	}

	private OvhAllPrices getPrices() throws IOException {
		final var pricesMap = getResource(this::getPricesUrl, null, DATABASE_LIST2);
		final OvhAllPrices result = new OvhAllPrices();
		final List<OvhAttrInstance> storages = new ArrayList<>();
		final List<OvhAttrInstance> archives = new ArrayList<>();
		final List<OvhAttrInstance> volumes = new ArrayList<>();
		final List<OvhAttrInstance> instances = new ArrayList<>();
		final List<OvhAttrInstance> snapshots = new ArrayList<>();
		final List<OvhAttrInstance> databases = new ArrayList<>();
		pricesMap.stream()
		.filter(p -> p.get("term") != null)
		.forEach(planPrice -> {
			planPrice.keySet().stream().filter(prop -> !prop.equals("term") && !prop.equals("planCode")&& !prop.equals("regions") && !prop.equals("attr-0")&&
					!prop.equals("attr-1")&&!prop.equals("attr-2")&&!prop.equals("attr-3")&&!prop.equals("attr-4")&&!prop.equals("attr-5")&&!prop.equals("attr-6")&&!prop.equals("attr-7"))
					.forEach(region -> {
						// Each property is a region code
						var regionalPrice = (Map<String, String>) planPrice.get(region);
						var priceObj = new OvhAttrInstance();
						var planCode = (String) planPrice.get("planCode");
						priceObj.setPlanCode((String) planCode);
						priceObj.setRegion(region.toLowerCase());
						var monthlyPrice = regionalPrice.get("monthly");
						var hourlyPrice = regionalPrice.get("hourly");
						var monthlyWindowsPrice= regionalPrice.get("windows.monthly");
						var hourlyWindowsPrice = regionalPrice.get("windows.hourly");
						var monthlyLinuxPrice= regionalPrice.get("linux.monthly");
						var hourlyLinuxPrice = regionalPrice.get("linux.hourly");
						var ram = (String)planPrice.get("attr-1");
						var cpu = (String)planPrice.get("attr-2");
						var details = planCode.replace("databases.", "").replace(".hour.consumption", "");
						var tabDetails = details.split("-");

						priceObj=setPrice(monthlyWindowsPrice,priceObj,"windows.monthly");
						priceObj=setPrice(hourlyWindowsPrice,priceObj,"windows.hourly");
						priceObj=setPrice(monthlyLinuxPrice,priceObj,"linux.monthly");
						priceObj=setPrice(hourlyLinuxPrice,priceObj,"linux.hourly");
						priceObj=setPrice(monthlyPrice,priceObj,"MonthlyCost");
						priceObj=setPrice(hourlyPrice,priceObj,"HourlyCost");

						priceObj.setName((String)planPrice.get("attr-0"));
						
						var planPrice2 = planPrice;
						if (planCode.contains("storage") && !planCode.contains("bandwidth")) {
							var price = (String)planPrice.get("attr-1");
							if (!price.contains("Included")) {
								priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-1")));
								storages.add(priceObj);
							}		
						}
						
						if (planCode.contains("archive")) {
							priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-1")));
							archives.add(priceObj);
						}
						
						if (planCode.contains("volume.")) {
							if (planPrice.get("attr-3") != null ) {
								priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-3")));
							} else if (planPrice.get("attr-2") != null ) {
								priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-2")));
							}else {
								priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-1")));
							}
							volumes.add(priceObj);
						}
						
						if (planCode.contains("databases")|| planCode.contains("db1-")|| planCode.contains("db2-")) {		
							if (planPrice.get("attr-7") != null ) {
								priceObj.setRAM((Double)Double.parseDouble(ram.replaceAll("[/a-z A-Z]*", "").replaceAll("\\xA0", ""))*1024);
								priceObj.setCPU((Double)Double.parseDouble(cpu.replaceAll("[/a-z A-Z]*", "").replaceAll("\\xA0", "")));
								priceObj.setStorage((String)planPrice.get("attr-3"));
								priceObj.setPublicNetwork((String)planPrice.get("attr-4"));
								priceObj.setPrivateNetwork((String)planPrice.get("attr-5"));
								priceObj.setDedicatedNode((String)planPrice.get("attr-6"));
								priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-7")));
								priceObj.setEngine((String)tabDetails[0]);
								priceObj.setPlan((String)tabDetails[1]);
								priceObj.setFlavor((String)tabDetails[2]+"-"+tabDetails[3]);
								databases.add(priceObj);
							}else if (tabDetails[1] != null){
								priceObj.setRAM((Double)Double.parseDouble(ram.replaceAll("[/a-z A-Z]*", "").replaceAll("\\xA0", ""))*1024);
								priceObj.setCPU((Double)Double.parseDouble(cpu.replaceAll("[/a-z A-Z]*", "").replaceAll("\\xA0", "")));
								priceObj.setPublicNetwork((String)planPrice.get("attr-3"));
								priceObj.setPrivateNetwork((String)planPrice.get("attr-4"));
								priceObj.setDedicatedNode((String)planPrice.get("attr-5"));
								priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-6")));
								priceObj.setEngine((String)tabDetails[0]);
								priceObj.setPlan((String)tabDetails[1]);
								priceObj.setFlavor((String)tabDetails[2]+"-"+tabDetails[3]);
								databases.add(priceObj);
							}
						}
						
						// ai-notebook.ai1-1-cpu.minute.consumption
						if (planCode.contains("instance") && !planCode.contains("bandwidth")|| planCode.contains("b2-") || planCode.contains("c2-")
								|| planCode.contains("t1-") || planCode.contains("t2-")|| planCode.contains("i1-") && !planCode.contains(".ai1-1")|| planCode.contains("d2-")) {
							if (planPrice.get("attr-8") != null && planCode.contains("t1-") || planCode.contains("t2-") ) {
								priceObj.setRAM((Double)Double.parseDouble(ram.replaceAll("[/a-z A-Z]*", "").replaceAll("\\xA0", ""))*1024);
								priceObj.setCPU((Double)Double.parseDouble(cpu.replaceAll("[/a-z A-Z]*", "").replaceAll("\\xA0", "")));
								priceObj.setGPU((String)planPrice.get("attr-2"));
								priceObj.setStorage((String)planPrice.get("attr-4"));
								priceObj.setPublicNetwork((String)planPrice.get("attr-5"));
								priceObj.setPrivateNetwork((String)planPrice.get("attr-6"));
								priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-7")));
							} else if (planPrice.get("attr-7") != null && planCode.contains("i1-") || planCode.contains("t1-") ) {
								priceObj.setRAM((Double)Double.parseDouble(ram.replaceAll("[/a-z A-Z]*", "").replaceAll("\\xA0", ""))*1024);
								priceObj.setCPU((Double)Double.parseDouble(cpu.replaceAll("[/a-z A-Z]*", "").replaceAll("\\xA0", "")));
								priceObj.setStorage((String)planPrice.get("attr-3"));
								priceObj.setNVMeDisks((String)planPrice.get("attr-4"));
								priceObj.setPublicNetwork((String)planPrice.get("attr-5"));
								priceObj.setPrivateNetwork((String)planPrice.get("attr-6"));
								priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-7")));
							}else if(planPrice.get("attr-2")!= null){
								priceObj.setRAM((Double)Double.parseDouble(ram.replaceAll("[/a-z A-Z]*", "").replaceAll("\\xA0", ""))*1024);
								priceObj.setCPU((Double)Double.parseDouble(cpu.replaceAll("[/a-z A-Z]*", "").replaceAll("\\xA0", "")));
								priceObj.setStorage((String)planPrice.get("attr-3"));
								priceObj.setPublicNetwork((String)planPrice.get("attr-4"));
								priceObj.setPrivateNetwork((String)planPrice.get("attr-5"));
								var price = (String)planPrice.get("attr-6");
								if (price != null) {
									priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-6")));
								} else {
									log.info("not price");
								}
							}
							instances.add(priceObj);
						}
						
						if (planCode.contains("snapshot.")) {
							priceObj.setPrice((Double)Double.parseDouble((String)planPrice.get("attr-1")));
							snapshots.add(priceObj);
						}
						
					});
		});
		result.setArchive(archives);
		result.setDatabases(databases);
		result.setInstances(instances);
		result.setSnapshots(snapshots);
		result.setStorage(storages);
		result.setVolumes(volumes);
		return result;
	}
	
	private OvhAttrInstance setPrice(String value ,OvhAttrInstance priceObj , String attr ) {
		if (value != null ) {
			if(StringUtils.countMatches(value,".")==1 && value.contains(",") == true && value.contains("Included") == false ) { //Pattern.compile(value).matcher([/a-z A-Z]*).find()
				value = value.replace(",", ".");
				value = value.replaceFirst("\\.", "").replaceAll("[/a-z A-Z]*", "").replaceAll("\\s+","");
				switch(attr){
				   
			       case "linux.monthly": 
			    	   priceObj.setMonthlyLinuxCost((Double) Double.parseDouble(value));
			           break;
			   
			       case "linux.hourly":
			    	   priceObj.setHourlyLinuxCost((Double) Double.parseDouble(value));
			           break;
			   
			       case "windows.monthly":
			    	   priceObj.setMonthlyWindowsCost((Double) Double.parseDouble(value));
			           break;
			           
			       case "windows.hourly":
			    	   priceObj.setHourlyWindowsCost((Double) Double.parseDouble(value));
			           break;
			           
			       case "MonthlyCost":
			    	   priceObj.setMonthlyCost((Double) Double.parseDouble(value));
			           break;
			           
			       case "HourlyCost":
			    	   priceObj.setHourlyCost((Double) Double.parseDouble(value));
			           break;
			         
			   }
			}else if (value.contains("Included") == false) {
				value = value.replace(",", "");
				switch(attr){
				   
			       case "linux.monthly": 
						priceObj.setMonthlyLinuxCost((Double) Double.parseDouble(value.replaceAll("[/a-z A-Z]*", "")));
			           break;
			   
			       case "linux.hourly":
						priceObj.setHourlyLinuxCost((Double) Double.parseDouble(value.replaceAll("[/a-z A-Z]*", "")));
			           break;
			   
			       case "windows.monthly":
						priceObj.setMonthlyWindowsCost((Double) Double.parseDouble(value.replaceAll("[/a-z A-Z]*", "")));
			           break;
			           
			       case "windows.hourly":
						priceObj.setHourlyWindowsCost((Double) Double.parseDouble(value.replaceAll("[/a-z A-Z]*", "")));
			           break;
			           
			       case "MonthlyCost":
						priceObj.setMonthlyCost((Double) Double.parseDouble(value.replaceAll("[/a-z A-Z]*", "")));
			           break;
			         
			       case "HourlyCost":
						priceObj.setHourlyCost((Double) Double.parseDouble(value.replaceAll("[/a-z A-Z]*", "")));
			           break;
			   }
			}
		}
		return priceObj;
	}

	private void installInstancePrice(final UpdateContext context, final OvhAttrInstance instance, final ProvInstancePriceTerm hourlyTerm,
			final ProvInstancePriceTerm monthlyTerm) {
		if (instance == null) {
			log.warn("Unknown instance");
			return;
		}
		
		if(!isEnabledType(context, instance.getPlanCode())){
			return;
		}
		
		final var region = installRegion(context, instance.getRegion().toLowerCase());
		
		// Mark this region as used
		context.getUsedRegions().add(instance.getRegion().toLowerCase());
					
		final var type = installInstanceType(context, instance.getName(), instance);
		
		if (instance.getHourlyWindowsCost()!= null ) {
			installInstancePrice2( context,  hourlyTerm,  getOs("windows"), instance, region ,  type ,  monthlyTerm ,  "windows" );
		}
		
		if(instance.getHourlyLinuxCost()!= null) {
			installInstancePrice2( context,  hourlyTerm,  getOs("linux"), instance, region ,  type ,  monthlyTerm ,  "linux" );
		}
		
		installInstancePrice2( context,  hourlyTerm,  getOs("linux"), instance, region ,  type ,  monthlyTerm , "");
		
		// Mark this region as used
		context.getUsedRegions().add(instance.getRegion().toLowerCase());

	}
	
	private void installInstancePrice2(UpdateContext context, ProvInstancePriceTerm hourlyTerm, VmOs os,
			OvhAttrInstance instance,ProvLocation region , ProvInstanceType type , ProvInstancePriceTerm monthlyTerm , String value) {
		
			if (!isEnabledOs(context, os)) {
					return;			
			}
			
			switch (value) {
			case "windows" :
				installInstancePrice(context, hourlyTerm, os, type, instance.getHourlyWindowsCost() * context.getHoursMonth(), region);
				installInstancePrice(context, monthlyTerm, os, type, instance.getMonthlyWindowsCost(), region);
				break;
				
			case "linux" :
				installInstancePrice(context, hourlyTerm, os, type, instance.getHourlyLinuxCost() * context.getHoursMonth(), region);
				installInstancePrice(context, monthlyTerm, os, type, instance.getMonthlyLinuxCost(), region);
				break;
				
			default :
				if (instance.getHourlyCost()!= null){
					installInstancePrice(context, hourlyTerm, os, type, instance.getHourlyCost() * context.getHoursMonth(), region);
				}
				
				if (instance.getMonthlyCost()!= null) {
					installInstancePrice(context, monthlyTerm, os, type, instance.getMonthlyCost(), region);
				}
			break;
			
			}
	}

	@Override
	protected boolean isEnabledEngine(final AbstractUpdateContext context, final String engine) {
		// REDIS is not really an SGBD
		return super.isEnabledEngine(context, engine) && !engine.toUpperCase().equalsIgnoreCase("REDIS");
	}

	/**
	 * Install the storage types and prices.
	 */
	private void installStoragePrices(final UpdateContext context, final OvhAllPrices prices) {
		nextStep(context, "install-vm-storage");

		installStorage(context, prices.getSnapshots(), p -> "snapshots", (t, p) -> {
			t.setIops(7500);
			t.setThroughput(300);
			t.setLatency(Rate.LOW);
			t.setDurability9(11);
			t.setName("Storage replicated x3");
			t.setOptimized(ProvStorageOptimized.DURABILITY);

		});

		installStorage(context, prices.getStorage(), p -> "storage", (t, p) -> {
			t.setIops(5000);
			t.setThroughput(200);
			t.setLatency(Rate.GOOD);
			t.setDurability9(11);
			t.setName("Object Storage");
			t.setOptimized(ProvStorageOptimized.DURABILITY);

		});
		
		installStorage(context, prices.getVolumes(), p -> p.getPlanCode().replace(".consumption", "").replace(".snapshot", ""), (t, p) -> {
			t.setIops(7500);
			t.setThroughput(300);
			t.setInstanceType("%");
			t.setLatency(Rate.BEST);
			t.setDurability9(7);
			t.setMaximal(4 * 1024d); // 4TiB
			switch (p.getName()) {
			case "volume.classic": {
				t.setIops(250);
				t.setName("Classic");
				break;
			}
			case "volume.high-speed": {
				t.setIops(3000);
				t.setName("High speed");
				break;
			}
			case "volume.high-speed-gen2": {
				// TODO Missing "volume.high-speed-gen2" from the API price
				t.setIops(20000);
				t.setName("High speed Gen2");
				break;
			}
			}
			t.setOptimized(ProvStorageOptimized.IOPS);

		});

		installStorage(context, prices.getArchive(), p -> "archive", (t, p) -> {
			t.setIops(7500);
			t.setThroughput(300);
			t.setLatency(Rate.WORST);
			t.setDurability9(11);
			t.setName("Cloud Archive");
			t.setOptimized(ProvStorageOptimized.DURABILITY);
		});

	}
	
	private <P extends OvhAttrInstance> void installStorage(UpdateContext context, final List<P> prices,
			final Function<P, String> toCodeType, final BiConsumer<ProvStorageType, P> filler) {
		prices.stream().filter(p -> isEnabledRegion(context, p.getRegion().toLowerCase())).forEach(p -> {
			final var codeType = toCodeType.apply(p);
			final var type = installStorageType(context, codeType, filler, p);
			final var region = p.getRegion().toLowerCase();
			installStoragePrice(context, region, type, p.getPrice(), region + "/" + type.getCode());
		});
	}

	private VmOs getOs(final String osName) {
		return EnumUtils.getEnum(VmOs.class, osName.toUpperCase());
	}

	/**
	 * Install or update a storage type.
	 */
	//extends OvhStorage
	private <P extends OvhAttrInstance> ProvStorageType installStorageType(final UpdateContext context, final String code,
			final BiConsumer<ProvStorageType, P> aType, final P price) {
		final var type = context.getStorageTypes().computeIfAbsent(code, c -> {
			final var newType = new ProvStorageType();
			newType.setNode(context.getNode());
			newType.setCode(c);
			return newType;
		});

		return copyAsNeeded(context, type, t -> {
			t.setName(code /* human readable name */);
			t.setMinimal(1);
			t.setIncrement(null);
			t.setAvailability(99d);
			aType.accept(t, price);
		}, stRepository);
	}

	/**
	 * Install or update a storage price.
	 */
	private void installStoragePrice(final UpdateContext context, final String region, final ProvStorageType type,
			final double cost, final String code) {
		final var price = context.getPreviousStorage().computeIfAbsent(code, c -> {
			final var newPrice = new ProvStoragePrice();
			newPrice.setType(type);
			newPrice.setCode(c);
			return newPrice;
		});

		copyAsNeeded(context, price, p -> {
			p.setLocation(installRegion(context, region));
			p.setType(type);
		});

		// Update the cost
		saveAsNeeded(context, price, cost, spRepository);
	}

	/**
	 * Install a new instance price as needed.
	 */
	private void installInstancePrice(final UpdateContext context, final ProvInstancePriceTerm term, final VmOs os,
			final ProvInstanceType type, final double monthlyCost, final ProvLocation region) {
		final var price = context.getPrevious()
				.computeIfAbsent(os.name() + "/" + region.getName() + "/" + term.getCode() + "/" + type.getCode(), code -> {
					// New instance price (not update mode)
					final var newPrice = new ProvInstancePrice();
					newPrice.setCode(code);
					return newPrice;
				});
		copyAsNeeded(context, price, p -> {
			p.setLocation(region);
			p.setOs(os);
			p.setTerm(term);
			p.setTenancy(ProvTenancy.SHARED);
			p.setType(type);
			p.setPeriod(term.getPeriod());
		});

		// Update the cost
		ipRepository.findAll();
		saveAsNeeded(context, price, monthlyCost, ipRepository);
	}

	/**
	 * Install a new instance type as needed.
	 */
	private ProvInstanceType installInstanceType(final UpdateContext context, final String code,
			final OvhAttrInstance aType) {
		final var type = context.getInstanceTypes().computeIfAbsent(code, c -> {
			// New instance type (not update mode)
			final var newType = new ProvInstanceType();
			newType.setNode(context.getNode());
			newType.setCode(c);
			return newType;
		});

		// Merge as needed
		return copyAsNeeded(context, type, t -> {
			t.setName(code);
			t.setCpu(aType.getCPU());
			t.setRam((int) Math.ceil(aType.getRAM())); // Convert in MiB / 1000 * 1024
			t.setDescription("{Disk: " + aType.getStorage() + "}");
			t.setAutoScale(false);

			// Rating
			t.setCpuRate(Rate.MEDIUM);
			t.setRamRate(Rate.MEDIUM);
			t.setNetworkRate(Rate.MEDIUM);
			t.setStorageRate(Rate.MEDIUM);
		}, itRepository);
	}

	/**
	 * Install a new price term as needed and complete the specifications.
	 */
	protected ProvInstancePriceTerm installPriceTerm(final UpdateContext context, final String code, final int period) {
		final var term = context.getPriceTerms().computeIfAbsent(code, t -> {
			final var newTerm = new ProvInstancePriceTerm();
			newTerm.setNode(context.getNode());
			newTerm.setCode(t);
			return newTerm;
		});

		// Complete the specifications
		return copyAsNeeded(context, term, t -> {
			t.setName(code /* human readable name */);
			t.setPeriod(period);
			t.setReservation(false);
			t.setConvertibleFamily(false);
			t.setConvertibleType(false);
			t.setConvertibleLocation(false);
			t.setConvertibleOs(false);
			t.setEphemeral(false);
		});
	}

	/**
	 * Install a new database type as needed.
	 */
	private ProvDatabaseType installDatabaseType(final UpdateContext context, final String code,
			final String aType, final OvhAttrInstance database) {
		final var type = context.getDatabaseTypes().computeIfAbsent(code, c -> {
			final var newType = new ProvDatabaseType();
			newType.setNode(context.getNode());
			newType.setCode(c);
			return newType;
		});

		// Merge as needed
		return copyAsNeeded(context, type, t -> {
			t.setName(code);
			t.setCpu(database.getCPU());
			t.setRam(database.getRAM()); // Convert to MiB * 1024.0
			t.setAutoScale(false);
			//t.setDescription(String.format(
			//		"{\"version\":\"%s\",\"backup\":\"%s\",\"minDiskSize\":\"%s\",\"maxDiskSize\":\"%s\",\"minNodeNumber\":\"%s\","
			//				+ "\"maxNodeNumber\":\"%s\",\"network\":\"%s\",\"storage\":\"%s\",\"backupRetention\":\"%s\",\"description\":\"%s\"}",
			//				database.getVersion(), database.getBackup(), database.getMinDiskSize(), database.getMaxDiskSize(),
			//				database.getMinNodeNumber(), database.getMaxNodeNumber(), database.getNetwork(), database.getStorage(),
			//				database.getBackupRetention(), database.getDescription()));

			// Rating
			t.setCpuRate(Rate.MEDIUM);
			t.setRamRate(Rate.MEDIUM);
			t.setNetworkRate(Rate.MEDIUM);
			t.setStorageRate(Rate.MEDIUM);
		}, dtRepository);
	}

	/**
	 * Install a new instance price as needed.
	 */
	private void installDatabasePrice(final UpdateContext context, final ProvInstancePriceTerm term,
			final String localCode, final ProvDatabaseType type, final double monthlyCost, final String engine,
			final ProvLocation region) {
		final var price = context.getPreviousDatabase().computeIfAbsent(region.getName() + "/" + localCode, c -> {
			// New instance price
			final var newPrice = new ProvDatabasePrice();
			newPrice.setCode(c);
			return newPrice;
		});

		copyAsNeeded(context, price, p -> {
			p.setLocation(region);
			p.setEngine(engine.toUpperCase(Locale.ENGLISH));
			p.setTerm(term);
			p.setType(type);
			p.setPeriod(term.getPeriod());
		});

		// Update the cost
		saveAsNeeded(context, price, round3Decimals(monthlyCost), dpRepository);
	}

}