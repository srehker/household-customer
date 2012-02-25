/*
 * Copyright 2009-2012 the original author or authors.
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

package org.powertac.householdcustomer.appliances;

import java.util.Properties;
import java.util.Random;
import java.util.Vector;

import org.powertac.common.configurations.VillageConstants;

/**
 * The Other appliances contain several type of appliances that cannot be in any
 * other category of appliances such as air condition or small heaters and so
 * on. They works only when someone is at home. So it's a not shifting
 * appliance.
 * 
 * @author Antonios Chrysopoulos
 * @version 1.5, Date: 2.25.12
 */
public class Others extends NotShiftingAppliance
{

  @Override
  public void initialize (String household, Properties conf, Random gen)
  {
    // Filling the base variables
    name = household + " Others";
    saturation = 1;
    power = (int) (VillageConstants.CONSUMER_ELECTRONICS_POWER_VARIANCE * gen.nextGaussian() + VillageConstants.CONSUMER_ELECTRONICS_POWER_MEAN);
    cycleDuration = VillageConstants.CONSUMER_ELECTRONICS_DURATION_CYCLE;
    times = Integer.parseInt(conf.getProperty("OthersDailyTimes")) + applianceOf.getMembers().size();
    createWeeklyOperationVector(times, gen);

  }

  @Override
  Vector<Boolean> createDailyPossibilityOperationVector (int day)
  {
    Vector<Boolean> possibilityDailyOperation = new Vector<Boolean>();

    // Appliances under the other category need to operate only when someone is
    // in the house
    for (int j = 0; j < VillageConstants.QUARTERS_OF_DAY; j++) {
      if (applianceOf.isEmpty(day, j) == false)
        possibilityDailyOperation.add(true);
      else
        possibilityDailyOperation.add(false);
    }

    return possibilityDailyOperation;
  }

  @Override
  public void fillDailyFunction (int weekday, Random gen)
  {
    // Initializing and Creating auxiliary variables
    loadVector = new Vector<Integer>();
    dailyOperation = new Vector<Boolean>();
    Vector<Boolean> operation = operationVector.get(weekday);

    // For each quarter of a day
    for (int i = 0; i < VillageConstants.QUARTERS_OF_DAY; i++) {
      if (operation.get(i) == true) {
        boolean flag = true;
        int counter = 0;
        while ((flag) && (i < VillageConstants.QUARTERS_OF_DAY) && (counter >= 0)) {
          if (applianceOf.isEmpty(weekday, i) == false) {
            loadVector.add(power);
            dailyOperation.add(true);
            counter--;
            if (counter < 0)
              flag = false;
          } else {
            loadVector.add(0);
            dailyOperation.add(false);
            i++;
            if (i < VillageConstants.QUARTERS_OF_DAY && operation.get(i) == true)
              counter++;
          }
        }
      } else {
        loadVector.add(0);
        dailyOperation.add(false);
      }
    }
    weeklyLoadVector.add(loadVector);
    weeklyOperation.add(dailyOperation);
  }

  @Override
  public void refresh (Random gen)
  {
    createWeeklyOperationVector(times, gen);
    fillWeeklyFunction(gen);
    createWeeklyPossibilityOperationVector();
  }

}
