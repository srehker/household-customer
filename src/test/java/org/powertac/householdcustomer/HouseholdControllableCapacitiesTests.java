/*
 * Copyright 2010-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an
 * "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.powertac.householdcustomer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyDouble;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.MapConfiguration;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powertac.common.Broker;
import org.powertac.common.Competition;
import org.powertac.common.CustomerInfo;
import org.powertac.common.Rate;
import org.powertac.common.Tariff;
import org.powertac.common.TariffSpecification;
import org.powertac.common.TariffSubscription;
import org.powertac.common.TariffTransaction;
import org.powertac.common.TimeService;
import org.powertac.common.Timeslot;
import org.powertac.common.WeatherReport;
import org.powertac.common.config.Configurator;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.interfaces.Accounting;
import org.powertac.common.interfaces.ServerConfiguration;
import org.powertac.common.interfaces.TariffMarket;
import org.powertac.common.msg.TariffRevoke;
import org.powertac.common.msg.TariffStatus;
import org.powertac.common.repo.BrokerRepo;
import org.powertac.common.repo.CustomerRepo;
import org.powertac.common.repo.RandomSeedRepo;
import org.powertac.common.repo.TariffRepo;
import org.powertac.common.repo.TariffSubscriptionRepo;
import org.powertac.common.repo.TimeslotRepo;
import org.powertac.common.repo.WeatherReportRepo;
import org.powertac.householdcustomer.configurations.VillageConstants;
import org.powertac.householdcustomer.customers.Village;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * @author Antonios Chrysopoulos
 * @version 1.5, Date: 2.25.12
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-config.xml" })
@DirtiesContext
public class HouseholdControllableCapacitiesTests
{

  @Autowired
  private TimeService timeService;

  @Autowired
  private Accounting mockAccounting;

  @Autowired
  private TariffMarket mockTariffMarket;

  @Autowired
  private ServerConfiguration mockServerProperties;

  @Autowired
  private HouseholdCustomerService householdCustomerService;

  @Autowired
  private TariffRepo tariffRepo;

  @Autowired
  private CustomerRepo customerRepo;

  @Autowired
  private TariffSubscriptionRepo tariffSubscriptionRepo;

  @Autowired
  private TimeslotRepo timeslotRepo;

  @Autowired
  private WeatherReportRepo weatherReportRepo;

  @Autowired
  private BrokerRepo brokerRepo;

  @Autowired
  private RandomSeedRepo randomSeedRepo;

  private Configurator config;
  private Instant exp;
  private Broker broker1;
  private Instant now;
  private TariffSpecification defaultTariffSpec, defaultTariffSpecControllable;
  private Tariff defaultTariff, defaultTariffControllable;
  private Competition comp;
  private List<Object[]> accountingArgs;

  @Before
  public void setUp ()
  {
    customerRepo.recycle();
    brokerRepo.recycle();
    tariffRepo.recycle();
    tariffSubscriptionRepo.recycle();
    randomSeedRepo.recycle();
    timeslotRepo.recycle();
    weatherReportRepo.recycle();
    weatherReportRepo.runOnce();
    householdCustomerService.clearConfiguration();
    reset(mockAccounting);
    reset(mockServerProperties);

    // create a Competition, needed for initialization
    comp = Competition.newInstance("household-customer-test");
    broker1 = new Broker("Joe");

    now = new DateTime(2011, 1, 10, 0, 0, 0, 0, DateTimeZone.UTC).toInstant();
    timeService.setCurrentTime(now);
    timeService.setBase(now.getMillis());
    exp = now.plus(TimeService.WEEK * 10);
    //
    // defaultTariffSpec =
    // new TariffSpecification(broker1, PowerType.CONSUMPTION)
    // .withExpiration(exp).withMinDuration(TimeService.WEEK * 8)
    // .addRate(new Rate().withValue(-0.222));

    defaultTariffSpec =
      new TariffSpecification(broker1, PowerType.CONSUMPTION)
              .withExpiration(exp).addRate(new Rate().withValue(-0.222));
    defaultTariff = new Tariff(defaultTariffSpec);
    defaultTariff.init();
    defaultTariff.setState(Tariff.State.OFFERED);

    tariffRepo.setDefaultTariff(defaultTariffSpec);

    defaultTariffSpecControllable =
      new TariffSpecification(broker1, PowerType.INTERRUPTIBLE_CONSUMPTION)
              .withExpiration(exp).withMinDuration(TimeService.WEEK * 8)
              .addRate(new Rate().withValue(-0.121).withMaxCurtailment(0.3));
    defaultTariffControllable = new Tariff(defaultTariffSpecControllable);
    defaultTariffControllable.init();
    defaultTariffControllable.setState(Tariff.State.OFFERED);

    tariffRepo.setDefaultTariff(defaultTariffSpecControllable);

    when(mockTariffMarket.getDefaultTariff(PowerType.CONSUMPTION))
            .thenReturn(defaultTariff);
    when(mockTariffMarket.getDefaultTariff(PowerType.INTERRUPTIBLE_CONSUMPTION))
            .thenReturn(defaultTariffControllable);

    accountingArgs = new ArrayList<Object[]>();

    // mock the AccountingService, capture args
    doAnswer(new Answer() {
      public Object answer (InvocationOnMock invocation)
      {
        Object[] args = invocation.getArguments();
        accountingArgs.add(args);
        return null;
      }
    }).when(mockAccounting)
            .addTariffTransaction(isA(TariffTransaction.Type.class),
                                  isA(Tariff.class), isA(CustomerInfo.class),
                                  anyInt(), anyDouble(), anyDouble());

    // Set up serverProperties mock
    ReflectionTestUtils.setField(householdCustomerService,
                                 "serverPropertiesService",
                                 mockServerProperties);
    config = new Configurator();

    doAnswer(new Answer() {
      @Override
      public Object answer (InvocationOnMock invocation)
      {
        Object[] args = invocation.getArguments();
        config.configureSingleton(args[0]);
        return null;
      }
    }).when(mockServerProperties).configureMe(anyObject());

    TreeMap<String, String> map = new TreeMap<String, String>();
    map.put("householdcustomer.householdCustomerService.configFile1",
            "VillageType1.properties");
    map.put("householdcustomer.householdCustomerService.configFile2",
            "VillageType2.properties");
    map.put("householdcustomer.householdCustomerService.configFile3",
            "VillageType3.properties");
    map.put("householdcustomer.householdCustomerService.configFile4",
            "VillageType4.properties");
    map.put("common.competition.expectedTimeslotCount", "1440");
    Configuration mapConfig = new MapConfiguration(map);
    config.setConfiguration(mapConfig);
    config.configureSingleton(comp);

  }

  @After
  public void tearDown ()
  {
    timeService = null;
    mockAccounting = null;
    mockTariffMarket = null;
    mockServerProperties = null;
    householdCustomerService = null;
    tariffRepo = null;
    customerRepo = null;
    tariffSubscriptionRepo = null;
    timeslotRepo = null;
    weatherReportRepo = null;
    brokerRepo = null;
    randomSeedRepo = null;
    config = null;
    exp = null;
    broker1 = null;
    now = null;
    defaultTariffSpec = null;
    defaultTariffSpecControllable = null;
    defaultTariff = null;
    defaultTariffControllable = null;
    comp = null;
    accountingArgs = null;
  }

  public void initializeService ()
  {
    List<String> inits = new ArrayList<String>();
    inits.add("DefaultBroker");
    householdCustomerService.initialize(comp, inits);
    assertEquals("correct first configuration file", "VillageType1.properties",
                 householdCustomerService.getConfigFile1());
    assertEquals("correct second configuration file",
                 "VillageType2.properties",
                 householdCustomerService.getConfigFile2());
    assertEquals("correct third configuration file", "VillageType3.properties",
                 householdCustomerService.getConfigFile3());
    assertEquals("correct forth configuration file", "VillageType4.properties",
                 householdCustomerService.getConfigFile4());
    assertTrue(householdCustomerService.getDaysOfCompetition() >= Competition
            .currentCompetition().getExpectedTimeslotCount()
                                                                  / VillageConstants.HOURS_OF_DAY);
  }

  // @Repeat(20)
  @Test
  public void testNormalInitialization ()
  {
    initializeService();
  }

  // @Repeat(20)
  @Test
  public void testNormalInitializationWithoutConfig ()
  {
    TreeMap<String, String> map2 = new TreeMap<String, String>();
    map2.put("householdcustomer.householdCustomerService.configFile1", null);
    map2.put("householdcustomer.householdCustomerService.configFile2", null);
    map2.put("householdcustomer.householdCustomerService.configFile3", null);
    map2.put("householdcustomer.householdCustomerService.configFile4", null);
    Configuration mapConfig = new MapConfiguration(map2);
    config.setConfiguration(mapConfig);
    List<String> inits = new ArrayList<String>();
    inits.add("DefaultBroker");
    String result = householdCustomerService.initialize(comp, inits);
    assertEquals("correct return value", "HouseholdCustomer", result);
    assertEquals("correct configuration file", "VillageDefault.properties",
                 householdCustomerService.getConfigFile1());
    assertEquals("correct configuration file", "VillageDefault.properties",
                 householdCustomerService.getConfigFile2());
    assertEquals("correct configuration file", "VillageDefault.properties",
                 householdCustomerService.getConfigFile3());
    assertEquals("correct configuration file", "VillageDefault.properties",
                 householdCustomerService.getConfigFile4());
    assertTrue(householdCustomerService.getDaysOfCompetition() >= Competition
            .currentCompetition().getExpectedTimeslotCount()
                                                                  / VillageConstants.HOURS_OF_DAY);
  }

  // @Repeat(20)
  @Test
  public void testBogusInitialization ()
  {
    List<String> inits = new ArrayList<String>();
    String result = householdCustomerService.initialize(comp, inits);
    assertNull("return null value", result);
    inits.add("DefaultBroker");
  }

  // @Repeat(20)
  @Test
  public void testServiceInitialization ()
  {
    initializeService();
    assertEquals("Eight Consumers Created", 8, householdCustomerService
            .getVillageList().size());
    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      // Doing it again in order to check the correct configuration of the
      // SubscriptionMapping //
      customer.subscribeDefault();

      assertEquals("one subscription for CONSUMPTION customerInfo",
                   1,
                   tariffSubscriptionRepo
                           .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                 .get(0))
                           .size());
      assertEquals("one subscription for INTERRUPTIBLE_CONSUMPTION customerInfo",
                   1,
                   tariffSubscriptionRepo
                           .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                 .get(1))
                           .size());

      assertEquals("customer on DefaultTariff",
                   mockTariffMarket.getDefaultTariff(customer.getCustomerInfo()
                           .get(0).getPowerType()),
                   tariffSubscriptionRepo
                           .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                 .get(0))
                           .get(0).getTariff());
    }
  }

  // @Repeat(20)
  @Test
  public void testPowerConsumption ()
  {
    initializeService();

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      // Doing it again in order to check the correct configuration of the
      // SubscriptionMapping //
      customer.subscribeDefault();

    }

    timeService.setCurrentTime(now.plus(18 * TimeService.HOUR));
    householdCustomerService.activate(timeService.getCurrentTime(), 1);

    for (Village customer: householdCustomerService.getVillageList()) {

      assertFalse("Household consumed power for CONSUMPTION",
                  tariffSubscriptionRepo
                          .findActiveSubscriptionsForCustomer(customer
                                  .getCustomerInfo().get(0)) == null
                          || tariffSubscriptionRepo
                                  .findActiveSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                              .get(0))
                                  .get(0).getTotalUsage() < 0);
      assertFalse("Household consumed power for INTERRUPTIBLE_CONSUMPTION",
                  tariffSubscriptionRepo
                          .findActiveSubscriptionsForCustomer(customer
                                  .getCustomerInfo().get(1)) == null
                          || tariffSubscriptionRepo
                                  .findActiveSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                              .get(1))
                                  .get(0).getTotalUsage() < 0);
    }

    assertEquals("Tariff Transactions Created", 4 * householdCustomerService
            .getVillageList().size(), accountingArgs.size());

  }

  // @Repeat(20)
  @Test
  public void changeSubscription ()
  {
    initializeService();

    Rate r2 = new Rate().withValue(-0.222);
    Rate r3 = new Rate().withValue(-0.08).withMaxCurtailment(0.1);

    TariffSpecification tsc1 =
      new TariffSpecification(broker1, PowerType.CONSUMPTION)
              .withExpiration(now.plus(TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r2);
    TariffSpecification tsc2 =
      new TariffSpecification(broker1, PowerType.INTERRUPTIBLE_CONSUMPTION)
              .withExpiration(now.plus(TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r3);
    TariffSpecification tsc3 =
      new TariffSpecification(broker1, PowerType.CONSUMPTION)
              .withExpiration(now.plus(3 * TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r2);

    Tariff tariff1 = new Tariff(tsc1);
    tariff1.init();
    tariff1.setState(Tariff.State.OFFERED);
    Tariff tariff2 = new Tariff(tsc2);
    tariff2.init();
    tariff2.setState(Tariff.State.OFFERED);
    Tariff tariff3 = new Tariff(tsc3);
    tariff3.init();
    tariff3.setState(Tariff.State.OFFERED);

    assertEquals("Five tariffs", 5, tariffRepo.findAllTariffs().size());

    ArgumentCaptor<PowerType> powerArg =
      ArgumentCaptor.forClass(PowerType.class);

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      // Doing it again in order to check the correct configuration of the
      // SubscriptionMapping //
      customer.subscribeDefault();

    }

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);

      // Changing from default to another tariff
      TariffSubscription sub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), tariff1);
      sub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription sub2 =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), tariff2);
      sub2.subscribe(customer.getCustomerInfo().get(1).getPopulation());

      when(mockTariffMarket.getActiveTariffList(powerArg.capture()))
              .thenReturn(tariffRepo.findActiveTariffs(PowerType.CONSUMPTION))
              .thenReturn(tariffRepo
                                  .findActiveTariffs(PowerType.INTERRUPTIBLE_CONSUMPTION));

      // System.out.println("Subscriptions: " +
      // tariffSubscriptionRepo.findSubscriptionsForCustomer(customer.getCustomerInfo()).toString());

      customer.changeSubscription(mockTariffMarket.getDefaultTariff(customer
              .getCustomerInfo().get(0).getPowerType()), customer
              .getCustomerInfo().get(0));
      customer.changeSubscription(mockTariffMarket.getDefaultTariff(customer
              .getCustomerInfo().get(1).getPowerType()), customer
              .getCustomerInfo().get(1));

      assertFalse("Changed from default tariff",
                  tariffSubscriptionRepo
                          .findActiveSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                      .get(0))
                          .get(1).getTariff() == mockTariffMarket
                          .getDefaultTariff(customer.getCustomerInfo().get(0)
                                  .getPowerType()));

      assertFalse("Changed from default tariff for PowerType INTERRUPTIBLE_CONSUMPTION",
                  tariffSubscriptionRepo
                          .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                .get(1)).get(1)
                          .getTariff() == mockTariffMarket
                          .getDefaultTariff(customer.getCustomerInfo().get(1)
                                  .getPowerType()));

      // Changing back from the new tariff to the default one in order to check
      // every changeSubscription Method
      Tariff lastTariff =
        tariffSubscriptionRepo
                .findSubscriptionsForCustomer(customer.getCustomerInfo().get(0))
                .get(1).getTariff();
      Tariff lastTariff2 =
        tariffSubscriptionRepo
                .findSubscriptionsForCustomer(customer.getCustomerInfo().get(1))
                .get(1).getTariff();

      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      customer.changeSubscription(lastTariff, mockTariffMarket
              .getDefaultTariff(customer.getCustomerInfo().get(0)
                      .getPowerType()), customer.getCustomerInfo().get(0));
      customer.changeSubscription(lastTariff2, mockTariffMarket
              .getDefaultTariff(customer.getCustomerInfo().get(1)
                      .getPowerType()), customer.getCustomerInfo().get(1));

      assertTrue("Changed to default tariff for CONSUMPTION",
                 tariffSubscriptionRepo
                         .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                               .get(0)).get(0)
                         .getTariff() == mockTariffMarket
                         .getDefaultTariff(PowerType.CONSUMPTION));

      assertTrue("Changed to default tariff for INTERRUPTIBLE_CONSUMPTION",
                 tariffSubscriptionRepo
                         .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                               .get(1)).get(0)
                         .getTariff() == mockTariffMarket
                         .getDefaultTariff(PowerType.INTERRUPTIBLE_CONSUMPTION));

      sub.subscribe(customer.getHouses("SS").size());
      sub2.subscribe(customer.getHouses("SS").size());

      customer.changeSubscription(mockTariffMarket.getDefaultTariff(customer
              .getCustomerInfo().get(0).getPowerType()), lastTariff, customer
              .getHouses("SS").size(), customer.getCustomerInfo().get(0));
      customer.changeSubscription(mockTariffMarket.getDefaultTariff(customer
              .getCustomerInfo().get(1).getPowerType()), lastTariff2, customer
              .getHouses("SS").size(), customer.getCustomerInfo().get(1));

      assertFalse("Changed from default tariff for CONSUMPTION",
                  tariffSubscriptionRepo
                          .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                .get(0)).get(1)
                          .getTariff() == mockTariffMarket
                          .getDefaultTariff(PowerType.CONSUMPTION));

      assertFalse("Changed from default tariff for INTERRUPTIBLE_CONSUMPTION",
                  tariffSubscriptionRepo
                          .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                .get(1)).get(1)
                          .getTariff() == mockTariffMarket
                          .getDefaultTariff(PowerType.INTERRUPTIBLE_CONSUMPTION));

      sub.subscribe(customer.getHouses("NS").size());
      sub2.subscribe(customer.getHouses("NS").size());

      when(mockTariffMarket.getActiveTariffList(powerArg.capture()))
              .thenReturn(tariffRepo.findActiveTariffs(PowerType.CONSUMPTION))
              .thenReturn(tariffRepo
                                  .findActiveTariffs(PowerType.INTERRUPTIBLE_CONSUMPTION));

      customer.changeSubscription(mockTariffMarket.getDefaultTariff(customer
              .getCustomerInfo().get(0).getPowerType()), "NS", customer
              .getCustomerInfo().get(0));
      customer.changeSubscription(mockTariffMarket.getDefaultTariff(customer
              .getCustomerInfo().get(1).getPowerType()), "NS", customer
              .getCustomerInfo().get(1));

      assertFalse("Changed NS of CONSUMPTION from default tariff", customer
              .getSubscriptionMap().get("NS").getTariff() == mockTariffMarket
              .getDefaultTariff(customer.getCustomerInfo().get(0)
                      .getPowerType()));
      assertFalse("Changed NS of INTERRUPTIBLE_CONSUMPTION from default tariff",
                  customer.getControllableSubscriptionMap().get("NS")
                          .getTariff() == mockTariffMarket
                          .getDefaultTariff(customer.getCustomerInfo().get(1)
                                  .getPowerType()));

    }

  }

  // @Repeat(20)
  @Test
  public void revokeSubscription ()
  {
    initializeService();

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      // Doing it again in order to check the correct configuration of the
      // SubscriptionMapping //
      customer.subscribeDefault();

      assertEquals("one subscription for CONSUMPTION",
                   1,
                   tariffSubscriptionRepo
                           .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                 .get(0))
                           .size());
      assertEquals("one subscription for INTERRUPTIBLE_CONSUMPTION",
                   1,
                   tariffSubscriptionRepo
                           .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                 .get(1))
                           .size());

    }

    Rate r2 = new Rate().withValue(-0.222);
    Rate r3 = new Rate().withValue(-0.08).withMaxCurtailment(0.1);

    TariffSpecification tsc1 =
      new TariffSpecification(broker1, PowerType.CONSUMPTION)
              .withExpiration(now.plus(TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r2);
    TariffSpecification tsc2 =
      new TariffSpecification(broker1, PowerType.INTERRUPTIBLE_CONSUMPTION)
              .withExpiration(now.plus(TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r3);
    TariffSpecification tsc3 =
      new TariffSpecification(broker1, PowerType.CONSUMPTION)
              .withExpiration(now.plus(3 * TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r2);

    Tariff tariff1 = new Tariff(tsc1);
    tariff1.init();
    tariff1.setState(Tariff.State.OFFERED);
    Tariff tariff2 = new Tariff(tsc2);
    tariff2.init();
    tariff2.setState(Tariff.State.OFFERED);
    Tariff tariff3 = new Tariff(tsc3);
    tariff3.init();
    tariff3.setState(Tariff.State.OFFERED);

    assertEquals("Five consumption tariffs", 5, tariffRepo.findAllTariffs()
            .size());

    assertNotNull("first tariff found", tariff1);
    assertNotNull("second tariff found", tariff2);
    assertNotNull("third tariff found", tariff3);

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription tsd =
        tariffSubscriptionRepo
                .findSubscriptionForTariffAndCustomer(mockTariffMarket
                        .getDefaultTariff(PowerType.CONSUMPTION), customer
                        .getCustomerInfo().get(0));
      customer.unsubscribe(tsd, 3);
      TariffSubscription sub1 =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), tariff1);
      sub1.subscribe(3);
      TariffSubscription sub2 =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), tariff2);
      sub2.subscribe(3);
      TariffSubscription sub3 =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), tariff3);
      sub3.subscribe(4);

      TariffSubscription ts1 =
        tariffSubscriptionRepo
                .findSubscriptionForTariffAndCustomer(tariff1, customer
                        .getCustomerInfo().get(0));
      customer.unsubscribe(ts1, 2);
      TariffSubscription ts2 =
        tariffSubscriptionRepo
                .findSubscriptionForTariffAndCustomer(tariff2, customer
                        .getCustomerInfo().get(1));
      customer.unsubscribe(ts2, 1);
      TariffSubscription ts3 =
        tariffSubscriptionRepo
                .findSubscriptionForTariffAndCustomer(tariff3, customer
                        .getCustomerInfo().get(0));
      customer.unsubscribe(ts3, 2);
      assertEquals("3 Subscriptions for customerInfo CONSUMPTION",
                   3,
                   tariffSubscriptionRepo
                           .findActiveSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                       .get(0))
                           .size());
      assertEquals("3 Subscriptions for customerInfo INTERRUPTIBLE_CONSUMPTION",
                   2,
                   tariffSubscriptionRepo
                           .findActiveSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                       .get(1))
                           .size());
      timeService.setCurrentTime(timeService.getCurrentTime()
              .plus(TimeService.HOUR));

    }

    timeService.setCurrentTime(new Instant(timeService.getCurrentTime()
            .getMillis() + TimeService.HOUR));
    TariffRevoke tex = new TariffRevoke(tsc2.getBroker(), tsc2);
    tariff2.setState(Tariff.State.KILLED);
    assertTrue("tariff revoked", tariff2.isRevoked());

    householdCustomerService.activate(timeService.getCurrentTime(), 1);

    TariffStatus st2 =
      new TariffStatus(broker1, tariff3.getId(), tariff3.getId(),
                       TariffStatus.Status.success);

    TariffRevoke tex2 =
      new TariffRevoke(tariff3.getBroker(), tariff3.getTariffSpec());
    tariff3.setState(Tariff.State.KILLED);
    assertTrue("tariff revoked", tariff3.isRevoked());

    householdCustomerService.activate(timeService.getCurrentTime(), 1);

  }

  // @Repeat(20)
  @Test
  public void testPublishAndEvaluatingTariffs ()
  {
    initializeService();

    ArgumentCaptor<PowerType> powerArg =
      ArgumentCaptor.forClass(PowerType.class);

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      // Doing it again in order to check the correct configuration of the
      // SubscriptionMapping //
      customer.subscribeDefault();
    }

    Rate r2 = new Rate().withValue(-0.222);
    Rate r3 = new Rate().withValue(-0.08).withMaxCurtailment(0.1);

    TariffSpecification tsc1 =
      new TariffSpecification(broker1, PowerType.CONSUMPTION)
              .withExpiration(now.plus(TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r2);
    TariffSpecification tsc2 =
      new TariffSpecification(broker1, PowerType.INTERRUPTIBLE_CONSUMPTION)
              .withExpiration(now.plus(TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r3);
    TariffSpecification tsc3 =
      new TariffSpecification(broker1, PowerType.CONSUMPTION)
              .withExpiration(now.plus(3 * TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r2);

    Tariff tariff1 = new Tariff(tsc1);
    tariff1.init();
    tariff1.setState(Tariff.State.OFFERED);
    Tariff tariff2 = new Tariff(tsc2);
    tariff2.init();
    tariff2.setState(Tariff.State.OFFERED);
    Tariff tariff3 = new Tariff(tsc3);
    tariff3.init();
    tariff3.setState(Tariff.State.OFFERED);

    assertEquals("Five consumption tariffs", 5, tariffRepo.findAllTariffs()
            .size());

    assertNotNull("first tariff found", tariff1);
    assertNotNull("second tariff found", tariff2);
    assertNotNull("third tariff found", tariff3);

    List<Tariff> tclist1 = tariffRepo.findActiveTariffs(PowerType.CONSUMPTION);
    List<Tariff> tclist2 =
      tariffRepo.findActiveTariffs(PowerType.INTERRUPTIBLE_CONSUMPTION);

    assertEquals("3 consumption tariffs", 3, tclist1.size());
    assertEquals("2 interruptible consumption tariffs", 2, tclist2.size());

    when(mockTariffMarket.getActiveTariffList(powerArg.capture()))
            .thenReturn(tclist1).thenReturn(tclist2);

    // Test the function with different inputs, in order to get the same result.
    householdCustomerService.publishNewTariffs(tclist1);

    tclist1 = tariffRepo.findActiveTariffs(PowerType.CONSUMPTION);
    tclist2 = tariffRepo.findActiveTariffs(PowerType.INTERRUPTIBLE_CONSUMPTION);

    when(mockTariffMarket.getActiveTariffList(powerArg.capture()))
            .thenReturn(tclist1).thenReturn(tclist2);

    householdCustomerService.publishNewTariffs(tclist2);

  }

  @Test
  public void testAbnormalTariffs ()
  {
    initializeService();

    ArgumentCaptor<PowerType> powerArg =
      ArgumentCaptor.forClass(PowerType.class);

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      // Doing it again in order to check the correct configuration of the
      // SubscriptionMapping //
      customer.subscribeDefault();
    }

    Rate r2 = new Rate().withValue(-0.222);
    Rate r3 = new Rate().withValue(-8).withMaxCurtailment(0.1);
    Rate r4 = new Rate().withValue(-17000);

    TariffSpecification tsc1 =
      new TariffSpecification(broker1, PowerType.CONSUMPTION)
              .withExpiration(now.plus(TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r4);
    TariffSpecification tsc2 =
      new TariffSpecification(broker1, PowerType.INTERRUPTIBLE_CONSUMPTION)
              .withExpiration(now.plus(TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r3);
    TariffSpecification tsc3 =
      new TariffSpecification(broker1, PowerType.CONSUMPTION)
              .withExpiration(now.plus(3 * TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r2);

    Tariff tariff1 = new Tariff(tsc1);
    tariff1.init();
    tariff1.setState(Tariff.State.OFFERED);
    Tariff tariff2 = new Tariff(tsc2);
    tariff2.init();
    tariff2.setState(Tariff.State.OFFERED);
    Tariff tariff3 = new Tariff(tsc3);
    tariff3.init();
    tariff3.setState(Tariff.State.OFFERED);

    assertEquals("Five consumption tariffs", 5, tariffRepo.findAllTariffs()
            .size());

    assertNotNull("first tariff found", tariff1);
    assertNotNull("second tariff found", tariff2);
    assertNotNull("third tariff found", tariff3);

    List<Tariff> tclist1 = tariffRepo.findActiveTariffs(PowerType.CONSUMPTION);
    List<Tariff> tclist2 =
      tariffRepo.findActiveTariffs(PowerType.INTERRUPTIBLE_CONSUMPTION);

    assertEquals("3 consumption tariffs", 3, tclist1.size());
    assertEquals("2 interruptible consumption tariffs", 2, tclist2.size());

    when(mockTariffMarket.getActiveTariffList(powerArg.capture()))
            .thenReturn(tclist1).thenReturn(tclist2);

    // Test the function with different inputs, in order to get the same result.
    householdCustomerService.publishNewTariffs(tclist1);

  }

  // @Repeat(20)
  @Test
  public void testDailyShifting ()
  {
    initializeService();

    ArgumentCaptor<PowerType> powerArg =
      ArgumentCaptor.forClass(PowerType.class);

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      // Doing it again in order to check the correct configuration of the
      // SubscriptionMapping //
      customer.subscribeDefault();
    }

    // for (int i = 0; i < 10; i++) {

    Rate r0 =
      new Rate().withValue(-Math.random()).withDailyBegin(0).withDailyEnd(0);
    Rate r1 =
      new Rate().withValue(-Math.random()).withDailyBegin(1).withDailyEnd(1);
    Rate r2 =
      new Rate().withValue(-Math.random()).withDailyBegin(2).withDailyEnd(2);
    Rate r3 =
      new Rate().withValue(-Math.random()).withDailyBegin(3).withDailyEnd(3);
    Rate r4 =
      new Rate().withValue(-Math.random()).withDailyBegin(4).withDailyEnd(4);
    Rate r5 =
      new Rate().withValue(-Math.random()).withDailyBegin(5).withDailyEnd(5);
    Rate r6 =
      new Rate().withValue(-Math.random()).withDailyBegin(6).withDailyEnd(6);
    Rate r7 =
      new Rate().withValue(-Math.random()).withDailyBegin(7).withDailyEnd(7);
    Rate r8 =
      new Rate().withValue(-Math.random()).withDailyBegin(8).withDailyEnd(8);
    Rate r9 =
      new Rate().withValue(-Math.random()).withDailyBegin(9).withDailyEnd(9);
    Rate r10 =
      new Rate().withValue(-Math.random()).withDailyBegin(10).withDailyEnd(10);
    Rate r11 =
      new Rate().withValue(-Math.random()).withDailyBegin(11).withDailyEnd(11);
    Rate r12 =
      new Rate().withValue(-Math.random()).withDailyBegin(12).withDailyEnd(12);
    Rate r13 =
      new Rate().withValue(-Math.random()).withDailyBegin(13).withDailyEnd(13);
    Rate r14 =
      new Rate().withValue(-Math.random()).withDailyBegin(14).withDailyEnd(14);
    Rate r15 =
      new Rate().withValue(-Math.random()).withDailyBegin(15).withDailyEnd(15);
    Rate r16 =
      new Rate().withValue(-Math.random()).withDailyBegin(16).withDailyEnd(16);
    Rate r17 =
      new Rate().withValue(-Math.random()).withDailyBegin(17).withDailyEnd(17);
    Rate r18 =
      new Rate().withValue(-Math.random()).withDailyBegin(18).withDailyEnd(18);
    Rate r19 =
      new Rate().withValue(-Math.random()).withDailyBegin(19).withDailyEnd(19);
    Rate r20 =
      new Rate().withValue(-Math.random()).withDailyBegin(20).withDailyEnd(20);
    Rate r21 =
      new Rate().withValue(-Math.random()).withDailyBegin(21).withDailyEnd(21);
    Rate r22 =
      new Rate().withValue(-Math.random()).withDailyBegin(22).withDailyEnd(22);
    Rate r23 =
      new Rate().withValue(-Math.random()).withDailyBegin(23).withDailyEnd(23);

    TariffSpecification tsc1 =
      new TariffSpecification(broker1, PowerType.CONSUMPTION)
              .withExpiration(now.plus(TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8);
    tsc1.addRate(r0);
    tsc1.addRate(r1);
    tsc1.addRate(r2);
    tsc1.addRate(r3);
    tsc1.addRate(r4);
    tsc1.addRate(r5);
    tsc1.addRate(r6);
    tsc1.addRate(r7);
    tsc1.addRate(r8);
    tsc1.addRate(r9);
    tsc1.addRate(r10);
    tsc1.addRate(r11);
    tsc1.addRate(r12);
    tsc1.addRate(r13);
    tsc1.addRate(r14);
    tsc1.addRate(r15);
    tsc1.addRate(r16);
    tsc1.addRate(r17);
    tsc1.addRate(r18);
    tsc1.addRate(r19);
    tsc1.addRate(r20);
    tsc1.addRate(r21);
    tsc1.addRate(r22);
    tsc1.addRate(r23);

    Tariff tariff1 = new Tariff(tsc1);
    tariff1.init();
    tariff1.setState(Tariff.State.OFFERED);

    assertNotNull("first tariff found", tariff1);

    List<Tariff> tclist1 = tariffRepo.findActiveTariffs(PowerType.CONSUMPTION);
    List<Tariff> tclist2 =
      tariffRepo.findActiveTariffs(PowerType.INTERRUPTIBLE_CONSUMPTION);

    assertEquals("2 consumption tariffs", 2, tclist1.size());
    assertEquals("1 interruptible consumption tariffs", 1, tclist2.size());

    when(mockTariffMarket.getActiveTariffList(powerArg.capture()))
            .thenReturn(tclist1).thenReturn(tclist2);

    householdCustomerService.publishNewTariffs(tclist1);

    // }
    timeService.setBase(now.getMillis());
    timeService.setCurrentTime(timeService.getCurrentTime()
            .plus(TimeService.HOUR * 23));
    householdCustomerService.activate(timeService.getCurrentTime(), 1);
  }

  // @Repeat(20)
  @Test
  public void testWeather ()
  {
    initializeService();

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      // Doing it again in order to check the correct configuration of the
      // SubscriptionMapping //
      customer.subscribeDefault();
    }

    // for (int i = 0; i < 10; i++) {
    timeService.setBase(now.getMillis());
    timeService.setCurrentTime(timeService.getCurrentTime()
            .plus(TimeService.HOUR * 5));

    Timeslot ts1 = timeslotRepo.makeTimeslot(timeService.getCurrentTime());
    // log.debug(ts1.toString());
    double temperature = 40 * Math.random();
    WeatherReport wr = new WeatherReport(ts1, temperature, 2, 3, 4);
    weatherReportRepo.add(wr);
    householdCustomerService.activate(timeService.getCurrentTime(), 1);

    for (int i = 0; i < 30; i++) {
      timeService.setBase(now.getMillis());
      timeService.setCurrentTime(timeService.getCurrentTime()
              .plus(TimeService.HOUR * 1));
      ts1 = timeslotRepo.makeTimeslot(timeService.getCurrentTime());
      // log.debug(ts1.toString());
      temperature = 40 * Math.random();
      wr = new WeatherReport(ts1, temperature, 2, 3, 4);
      weatherReportRepo.add(wr);
      householdCustomerService.activate(timeService.getCurrentTime(), 1);
    }

    for (Village customer: householdCustomerService.getVillageList()) {
      customer.showAggDailyLoad("SS", 36);
    }

    // for (int i = 0; i < 10; i++) {
    // timeService.setBase(now.getMillis());
    // timeService.setCurrentTime(timeService.getCurrentTime().plus(TimeService.HOUR
    // 12));
    // householdCustomerService.activate(timeService.getCurrentTime(), 1);

    // }

  }

  @Test
  public void testAfterDaysOfCompetition ()
  {
    initializeService();

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      // Doing it again in order to check the correct configuration of the
      // SubscriptionMapping //
      customer.subscribeDefault();
    }

    for (Village customer: householdCustomerService.getVillageList()) {
      customer.showAggDailyLoad("SS", 0);
    }

    timeService.setBase(now.getMillis());
    timeService.setCurrentTime(timeService.getCurrentTime()
            .plus(TimeService.DAY * 1020));
    householdCustomerService.activate(timeService.getCurrentTime(), 1);

    timeService.setCurrentTime(timeService.getCurrentTime()
            .plus(TimeService.HOUR * 23));
    householdCustomerService.activate(timeService.getCurrentTime(), 1);

    Timeslot ts1 = timeslotRepo.makeTimeslot(timeService.getCurrentTime());
    // log.debug(ts1.toString());
    double temperature = 40 * Math.random();
    WeatherReport wr = new WeatherReport(ts1, temperature, 2, 3, 4);
    weatherReportRepo.add(wr);
    householdCustomerService.activate(timeService.getCurrentTime(), 1);

    for (int i = 1700; i < 1730; i++) {
      timeService.setCurrentTime(timeService.getCurrentTime()
              .plus(TimeService.HOUR * 1));
      ts1 = timeslotRepo.makeTimeslot(timeService.getCurrentTime());
      // log.debug(ts1.toString());

      temperature = 40 * Math.random();
      wr = new WeatherReport(ts1, temperature, 2, 3, 4);
      weatherReportRepo.add(wr);
      householdCustomerService.activate(timeService.getCurrentTime(), 1);

    }

  }

  // @Repeat(20)
  @Test
  public void testCurtailment ()
  {
    initializeService();

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription defaultSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(0), defaultTariff);
      defaultSub.subscribe(customer.getCustomerInfo().get(0).getPopulation());
      TariffSubscription defaultControllableSub =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), defaultTariffControllable);
      defaultControllableSub.subscribe(customer.getCustomerInfo().get(1)
              .getPopulation());

      // Doing it again in order to check the correct configuration of the
      // SubscriptionMapping //
      customer.subscribeDefault();
    }

    Rate r3 = new Rate().withValue(-0.08).withMaxCurtailment(0.1);

    TariffSpecification tsc1 =
      new TariffSpecification(broker1, PowerType.INTERRUPTIBLE_CONSUMPTION)
              .withExpiration(now.plus(TimeService.DAY))
              .withMinDuration(TimeService.WEEK * 8).addRate(r3);

    Tariff tariff1 = new Tariff(tsc1);
    tariff1.init();
    tariff1.setState(Tariff.State.OFFERED);

    assertEquals("Three tariffs", 3, tariffRepo.findAllTariffs().size());

    for (Village customer: householdCustomerService.getVillageList()) {

      TariffSubscription sub2 =
        tariffSubscriptionRepo.getSubscription(customer.getCustomerInfo()
                .get(1), tariff1);
      sub2.subscribe(customer.getHouses("SS").size());

      customer.changeSubscription(mockTariffMarket
                                          .getDefaultTariff(PowerType.INTERRUPTIBLE_CONSUMPTION),
                                  tariff1,
                                  "SS", customer.getCustomerInfo().get(1));

      assertFalse("Changed from default tariff for INTERRUPTIBLE_CONSUMPTION",
                  tariffSubscriptionRepo
                          .findSubscriptionsForCustomer(customer.getCustomerInfo()
                                                                .get(1)).get(1)
                          .getTariff() == mockTariffMarket
                          .getDefaultTariff(PowerType.INTERRUPTIBLE_CONSUMPTION));

      List<TariffSubscription> subs =
        tariffSubscriptionRepo.findSubscriptionsForCustomer(customer
                .getCustomerInfo().get(1));
      // System.out.println(customer.toString() + " " + subs.toString());

      for (TariffSubscription sub: subs)
        sub.postBalancingControl(1);

    }
    timeService.setBase(now.getMillis());
    timeService.setCurrentTime(timeService.getCurrentTime());
    householdCustomerService.activate(timeService.getCurrentTime(), 1);
    timeService.setCurrentTime(timeService.getCurrentTime()
            .plus(timeService.HOUR));
    householdCustomerService.activate(timeService.getCurrentTime(), 1);

  }
}
