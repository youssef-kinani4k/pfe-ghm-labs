package com.leadflow.tenant.dto;

import com.leadflow.crm.model.CrmSettingSpec;
import java.util.List;

/** Un fournisseur disponible et les champs que son formulaire doit proposer. */
public record CrmProviderView(String providerId, List<CrmSettingSpec> settings) {
}
