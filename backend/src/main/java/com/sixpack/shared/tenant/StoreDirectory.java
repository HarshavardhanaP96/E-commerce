package com.sixpack.shared.tenant;

import java.util.List;

/** Every store that still has data (all but deleted ones), for jobs that run once per store. */
public interface StoreDirectory {

    List<TenantRef> stores();
}
