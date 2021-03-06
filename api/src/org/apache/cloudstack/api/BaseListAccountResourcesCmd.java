// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.api;

import org.apache.cloudstack.context.CallContext;

import com.cloud.user.Account;

public abstract class BaseListAccountResourcesCmd extends BaseListDomainResourcesCmd {

    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "list resources by account. Must be used with the domainId parameter.")
    private String accountName;

    public String getAccountName() {
        return accountName;
    }

    public Boolean getDisplay() {
        Account caller = CallContext.current().getCallingAccount();
        if (caller.getType() == Account.ACCOUNT_TYPE_NORMAL) {
            return true;
        }
        return null;
    }
}
