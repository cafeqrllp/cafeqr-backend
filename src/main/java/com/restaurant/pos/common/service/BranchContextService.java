package com.restaurant.pos.common.service;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Centralizes branch (org) context resolution for multi-branch operations.
 *
 * <h3>Design Contract:</h3>
 * <ul>
 *   <li><b>READ</b> operations use {@link #getReadOrgId(UUID)} — returns the selected
 *       branch when one is active, or {@code null} for Super Admin all-branch reads.</li>
 *   <li><b>WRITE</b> operations use {@link #requireWriteOrgId(UUID)} — always requires a
 *       branch context; throws {@link BusinessException} if none is available.</li>
 * </ul>
 */
@Service
public class BranchContextService {

    /**
     * For READ operations: returns the effective org ID to filter data by.
     * <ul>
     *   <li>An explicit filter org takes precedence.</li>
     *   <li>Otherwise, returns the current org from tenant context.</li>
     *   <li>A Super Admin with no active org may read across branches.</li>
     * </ul>
     *
     * @param filterOrgId optional org ID filter
     * @return the org ID to filter by, or {@code null} for an all-branch read
     */
    public UUID getReadOrgId(@Nullable UUID filterOrgId) {
        if (filterOrgId != null) {
            return filterOrgId;
        }
        return TenantContext.getCurrentOrg();
    }

    /**
     * For report and billing reads that must stay within the active branch. A query
     * parameter cannot select a different branch. A Super Admin with the global
     * ("All branches", X-Org-ID = 0) selection gets {@code null}, meaning every branch
     * of their own client; a specific branch filter without an active branch is rejected.
     */
    public UUID getSelectedReadOrgId(@Nullable UUID filterOrgId) {
        UUID selectedOrgId = TenantContext.getCurrentOrg();
        if (selectedOrgId != null) {
            if (filterOrgId != null && !selectedOrgId.equals(filterOrgId)) {
                throw new BusinessException("The requested branch does not match the active branch.");
            }
            return selectedOrgId;
        }
        if (SecurityUtils.isSuperAdmin() && filterOrgId != null) {
            throw new BusinessException("The requested branch does not match the active branch.");
        }
        return null;
    }

    /**
     * For WRITE operations: always requires a branch context.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>For Super Admin, use an explicit org when one is supplied.</li>
     *   <li>Otherwise, use the current org from tenant context.</li>
     *   <li>If still {@code null}, throw a {@link BusinessException}.</li>
     * </ol>
     *
     * @param explicitOrgId optional org ID from the request payload
     * @return a non-null org ID
     * @throws BusinessException if no branch context is available
     */
    public UUID requireWriteOrgId(@Nullable UUID explicitOrgId) {
        if (explicitOrgId != null && SecurityUtils.isSuperAdmin()) {
            return explicitOrgId;
        }
        UUID selectedOrgId = TenantContext.getCurrentOrg();
        if (selectedOrgId != null) {
            return selectedOrgId;
        }
        throw new BusinessException(
            "A branch must be selected before performing this operation. " +
            "Use the branch picker in the header to select an active branch."
        );
    }
}
