package com.cloud.network.dao;

import java.util.List;

import javax.ejb.Local;

import org.apache.log4j.Logger;

import com.cloud.network.Site2SiteCustomerGatewayVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Local(value={Site2SiteCustomerGatewayDao.class})
public class Site2SiteCustomerGatewayDaoImpl extends GenericDaoBase<Site2SiteCustomerGatewayVO, Long> implements Site2SiteCustomerGatewayDao {
    private static final Logger s_logger = Logger.getLogger(Site2SiteCustomerGatewayDaoImpl.class);
    
    private final SearchBuilder<Site2SiteCustomerGatewayVO> AllFieldsSearch;

    protected Site2SiteCustomerGatewayDaoImpl() {
        AllFieldsSearch = createSearchBuilder();
        AllFieldsSearch.and("gatewayIp", AllFieldsSearch.entity().getGatewayIp(), SearchCriteria.Op.EQ);
        AllFieldsSearch.and("name", AllFieldsSearch.entity().getName(), SearchCriteria.Op.EQ);
        AllFieldsSearch.and("accountId", AllFieldsSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
        AllFieldsSearch.done();
    }
    
    @Override
    public Site2SiteCustomerGatewayVO findByGatewayIp(String ip) {
        SearchCriteria<Site2SiteCustomerGatewayVO> sc = AllFieldsSearch.create();
        sc.setParameters("gatewayIp", ip);
        return findOneBy(sc);
    }

    @Override
    public Site2SiteCustomerGatewayVO findByNameAndAccountId(String name, long accountId) {
        SearchCriteria<Site2SiteCustomerGatewayVO> sc = AllFieldsSearch.create();
        sc.setParameters("name", name);
        sc.setParameters("accountId", accountId);
        return findOneBy(sc);
    }

    @Override
    public List<Site2SiteCustomerGatewayVO> listByAccountId(long accountId) {
        SearchCriteria<Site2SiteCustomerGatewayVO> sc = AllFieldsSearch.create();
        sc.setParameters("accountId", accountId);
        return listBy(sc, null);
    }
}
