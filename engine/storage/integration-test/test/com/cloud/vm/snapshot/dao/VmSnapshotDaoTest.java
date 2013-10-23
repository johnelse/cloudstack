/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.vm.snapshot.dao;

import com.cloud.vm.snapshot.VMSnapshotDetailsVO;
import junit.framework.Assert;
import org.apache.cloudstack.storage.test.CloudStackTestNGBase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.inject.Inject;
import java.util.Map;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/storageContext.xml")
public class VmSnapshotDaoTest extends CloudStackTestNGBase {
    @Inject
    VMSnapshotDetailsDao vmsnapshotDetailsDao;

    @Test
    public void testVmSnapshotDetails() {
        VMSnapshotDetailsVO detailsVO = new VMSnapshotDetailsVO(1L, "test", "foo");
        vmsnapshotDetailsDao.persist(detailsVO);
        Map<String, String> details = vmsnapshotDetailsDao.getDetails(1L);
        Assert.assertTrue(details.containsKey("test"));
    }

}