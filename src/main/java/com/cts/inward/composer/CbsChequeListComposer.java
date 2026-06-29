package com.cts.inward.composer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventQueues;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Button;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Paging;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Timer;
import org.zkoss.zul.event.PagingEvent;

import com.cts.inward.dto.CbsAccountData;
import com.cts.inward.dto.CbsValidationResult;
import com.cts.inward.entity.InwardCheque;
import com.cts.inward.enums.CbsValidation;
import com.cts.inward.service.CbsValidationService;
import com.cts.inward.service.CbsValidationServiceImpl;
import com.cts.inward.service.InwardChequeMICRService;
import com.cts.inward.service.InwardChequeServiceMICRImpl;

public class CbsChequeListComposer extends SelectorComposer<Component> {

	private static final long serialVersionUID = 1L;
	private static final int PAGE_SIZE = 6;

	private final InwardChequeMICRService chequeService = new InwardChequeServiceMICRImpl();
	private final CbsValidationService cbsService = new CbsValidationServiceImpl();

	@Wire Listbox lbCbsChequeList;
	@Wire Paging pgCbsChequeList;
	@Wire Textbox tbCbsSearch;
	@Wire Listbox lbCbsStatusFilter;
	@Wire Timer cbsProcessTimer;

	// ===== FOOTER COMPONENTS =====
	@Wire Label lblCbsChequeCount;
	@Wire Label lblCbsPageInfo;
	@Wire Button btnCbsPrevPage;
	@Wire Button btnCbsNextPage;

	private List<CbsResultRow> allResults = new ArrayList<>();
	private List<CbsResultRow> filteredResults = new ArrayList<>();

	private Long currentBatchId = null;
	private int processingIndex = 0;
	private int currentPage = 0;

	/**
	 * Subscribes to the "batchContext" queue to receive batchDbId and trigger Phase
	 * 1 loading, and to "removeInvalidCheques" to filter out rejected rows after
	 * Return to RRF. Related to: CBS Validation page — wires this composer to
	 * page-level events.
	 */
	@Override
	public void doAfterCompose(Component comp) throws Exception {
		super.doAfterCompose(comp);

		pgCbsChequeList.setPageSize(PAGE_SIZE);
		cbsProcessTimer.stop();

		EventQueues.lookup("batchContext", EventQueues.DESKTOP, true).subscribe((Event event) -> {
			Object data = event.getData();
			if (data instanceof Long) {
				currentBatchId = (Long) data;
				loadAllChequesAndStartProcessing();
			}
		});

		EventQueues.lookup("removeInvalidCheques", EventQueues.DESKTOP, true).subscribe((Event event) -> {
			removeInvalidRowsFromList();
		});
	}

	/**
	 * Loads all cheques for the batch from DB, builds a CbsResultRow per cheque
	 * with result=null ("Processing..."), renders all rows immediately, then starts
	 * the timer for Phase 2.
	 */
	private void loadAllChequesAndStartProcessing() {
		if (currentBatchId == null || currentBatchId <= 0) return;

		List<InwardCheque> cheques;
		try {
			cheques = chequeService.getChequesByBatchId(currentBatchId);
		} catch (Exception e) {
			System.err.println("CbsChequeListComposer: error loading cheques: " + e.getMessage());
			return;
		}

		if (cheques == null || cheques.isEmpty()) return;

		allResults = new ArrayList<>();
		filteredResults = new ArrayList<>();
		processingIndex = 0;
		currentPage = 0;

		for (InwardCheque cheque : cheques) {
			allResults.add(new CbsResultRow(cheque));
		}

		System.out.println("CbsChequeListComposer: loaded " + allResults.size() + " cheques — showing all as Processing...");

		applyFilterAndRefresh();
		cbsProcessTimer.start();
	}

	/**
	 * Called every 100ms by the ZK Timer; validates next cheque, updates row labels
	 * in-place, stops timer + fires "cbsValidationComplete" when done.
	 */
	@Listen("onTimer = #cbsProcessTimer")
	public void onTimerTick() {
		if (processingIndex >= allResults.size()) {
			cbsProcessTimer.stop();
			onValidationComplete();
			return;
		}

		CbsResultRow row = allResults.get(processingIndex);
		processingIndex++;

		InwardCheque cheque = row.cheque;

		System.out.println("CbsChequeListComposer: processing " + processingIndex + "/" + allResults.size()
				+ " -> " + cheque.getChequeNo());

		CbsAccountData cbsData = null;
		String fetchErr = null;
		try {
			cbsData = cbsService.fetchAccountData(cheque.getAccountNo());
		} catch (Exception e) {
			fetchErr = "CBS Fetch Error";
			System.err.println("CbsChequeListComposer: CBS fetch failed for " + cheque.getChequeNo() + ": " + e.getMessage());
		}

		CbsValidationResult result;
		if (fetchErr != null) {
			result = CbsValidationResult.failure("Missing CBS Data");
		} else {
			try {
				result = cbsService.validateCheque(cheque, cbsData);
			} catch (Exception e) {
				result = CbsValidationResult.failure("Validation Error");
			}
		}

		CbsValidation cbsResult = result.isValid() ? CbsValidation.Valid : CbsValidation.Invalid;
		try {
			chequeService.updateCbsValidationResult(cheque.getId(), cbsResult, result.getReason());
			cheque.setCbsValidation(cbsResult);
			cheque.setErrorReason(result.getReason());
		} catch (Exception e) {
			System.err.println("CbsChequeListComposer: DB update failed for " + cheque.getChequeNo() + ": " + e.getMessage());
		}

		row.result = result;
		updateRowLabels(row);

		EventQueues.lookup("chequeStatusUpdated", EventQueues.DESKTOP, true)
				.publish(new Event("onChequeStatusUpdated", null, null));
	}

	/**
	 * Directly updates the statusLabel and reasonLabel for one row — flicker-free
	 * live update without re-rendering the whole page.
	 */
	private void updateRowLabels(CbsResultRow row) {
		if (row.statusLabel == null || row.reasonLabel == null) return;

		try {
			if (row.result.isValid()) {
				row.statusLabel.setValue("VALID");
				row.statusLabel.setSclass("cbs-status-badge cbs-badge-valid");
			} else {
				row.statusLabel.setValue("INVALID");
				row.statusLabel.setSclass("cbs-status-badge cbs-badge-invalid");
			}
			row.reasonLabel.setValue(nullSafe(row.result.getReason()));
		} catch (Exception e) {
			System.err.println("CbsChequeListComposer: label update skipped for "
					+ row.cheque.getChequeNo() + ": " + e.getMessage());
		}
	}

	/** Counts valid/invalid and publishes cbsValidationComplete. */
	private void onValidationComplete() {
		List<CbsValidationResult> results = allResults.stream()
				.map(row -> row.result).filter(r -> r != null).collect(Collectors.toList());

		long[] counts = cbsService.countValidAndInvalid(results);

		System.out.println("CbsChequeListComposer: all done. valid=" + counts[0] + " invalid=" + counts[1]);

		EventQueues.lookup("cbsValidationComplete", EventQueues.DESKTOP, true)
				.publish(new Event("onCbsValidationComplete", null, counts));
	}

	/** Removes INVALID rows after Return to RRF and re-renders. */
	private void removeInvalidRowsFromList() {
		allResults = allResults.stream()
				.filter(row -> row.result != null && row.result.isValid())
				.collect(Collectors.toList());
		System.out.println("CbsChequeListComposer: invalid rows removed. remaining=" + allResults.size());
		applyFilterAndRefresh();
	}

	@Listen("onChange = #tbCbsSearch")
	public void onSearch() {
		applyFilterAndRefresh();
	}

	@Listen("onSelect = #lbCbsStatusFilter")
	public void onFilterChange() {
		applyFilterAndRefresh();
	}

	/**
	 * Applies keyword + status filter, resets to page 0, re-renders.
	 */
	private void applyFilterAndRefresh() {
		String keyword = tbCbsSearch != null ? tbCbsSearch.getValue().trim().toLowerCase() : "";
		String statusFilter = getSelectedStatus();

		filteredResults = allResults.stream()
				.filter(row -> matchesStatus(row, statusFilter))
				.filter(row -> matchesKeyword(row, keyword))
				.collect(Collectors.toList());

		currentPage = 0;
		pgCbsChequeList.setTotalSize(filteredResults.size());
		pgCbsChequeList.setActivePage(0);
		renderPage(0);
	}

	private String getSelectedStatus() {
		if (lbCbsStatusFilter == null) return "ALL";
		Listitem selected = lbCbsStatusFilter.getSelectedItem();
		return (selected != null) ? selected.getValue().toString() : "ALL";
	}

	private boolean matchesStatus(CbsResultRow row, String status) {
		if ("ALL".equals(status)) return true;
		if (row.result == null) return false;
		if ("VALID".equals(status)) return row.result.isValid();
		if ("INVALID".equals(status)) return !row.result.isValid();
		return true;
	}

	private boolean matchesKeyword(CbsResultRow row, String keyword) {
		if (keyword.isEmpty()) return true;
		return contains(row.cheque.getChequeNo(), keyword) || contains(row.holderName, keyword);
	}

	private boolean contains(String field, String keyword) {
		return field != null && field.toLowerCase().contains(keyword);
	}

	@Listen("onPaging = #pgCbsChequeList")
	public void onPageChange(PagingEvent event) {
		currentPage = event.getActivePage();
		renderPage(currentPage);
	}

	// ===================================================
	// Pagination Button Listeners
	// ===================================================

	@Listen("onClick = #btnCbsPrevPage")
	public void onPrevPage() {
		if (currentPage > 0) {
			currentPage--;
			pgCbsChequeList.setActivePage(currentPage);
			renderPage(currentPage);
		}
	}

	@Listen("onClick = #btnCbsNextPage")
	public void onNextPage() {
		int totalPages = (filteredResults.size() + PAGE_SIZE - 1) / PAGE_SIZE;
		if (currentPage < totalPages - 1) {
			currentPage++;
			pgCbsChequeList.setActivePage(currentPage);
			renderPage(currentPage);
		}
	}

	/**
	 * Clears and rebuilds one page slice of filteredResults; stores Label
	 * references in each row for live Phase 2 updates. Also updates footer.
	 */
	private void renderPage(int pageIndex) {
		lbCbsChequeList.getItems().clear();

		int from = pageIndex * PAGE_SIZE;
		int to = Math.min(from + PAGE_SIZE, filteredResults.size());

		int rowNum = from + 1;
		for (int i = from; i < to; i++) {
			CbsResultRow row = filteredResults.get(i);
			lbCbsChequeList.appendChild(buildRow(row, rowNum++));
		}

		updateFooter();
	}

	// ===================================================
	// Footer Update
	// ===================================================

	private void updateFooter() {
		if (lblCbsChequeCount == null || lblCbsPageInfo == null
				|| btnCbsPrevPage == null || btnCbsNextPage == null) {
			System.err.println("WARNING: CBS footer components not wired. Check ZUL file.");
			return;
		}

		int total = filteredResults.size();
		int totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE;

		int fromIndex = currentPage * PAGE_SIZE + 1;
		int toIndex = Math.min((currentPage + 1) * PAGE_SIZE, total);

		// "Showing X-Y of Z"
		if (total == 0) {
			lblCbsChequeCount.setValue("Showing 0 of 0");
		} else {
			lblCbsChequeCount.setValue("Showing " + fromIndex + "-" + toIndex + " of " + total);
		}

		// "X of Y"
		int displayPage = Math.max(1, currentPage + 1);
		int displayTotal = Math.max(1, totalPages);
		lblCbsPageInfo.setValue(displayPage + " of " + displayTotal);

		// Disable/enable buttons
		btnCbsPrevPage.setDisabled(currentPage == 0 || total == 0);
		btnCbsNextPage.setDisabled(currentPage >= totalPages - 1 || total == 0);
	}

	private Listitem buildRow(CbsResultRow row, int rowNum) {
		Listitem item = new Listitem();

		item.appendChild(new Listcell(String.valueOf(rowNum)));

		Listcell cellChequeNo = new Listcell();
		Label lblChequeNo = new Label(nullSafe(row.cheque.getChequeNo()));
		lblChequeNo.setSclass("cheque-no-link");
		cellChequeNo.appendChild(lblChequeNo);
		item.appendChild(cellChequeNo);

		String accNo = row.cheque.getAccountNo();
		String masked = (accNo != null && accNo.length() >= 4)
				? "****" + accNo.substring(accNo.length() - 4) : "****";
		item.appendChild(new Listcell(masked));

		Label holderNameLabel = new Label(nullSafe(row.holderName));
		holderNameLabel.setSclass("cell-normal");
		Listcell holderCell = new Listcell();
		holderCell.appendChild(holderNameLabel);
		item.appendChild(holderCell);

		Label statusLabel;
		if (row.result == null) {
			statusLabel = new Label("Processing...");
			statusLabel.setSclass("cbs-status-badge cbs-badge-processing");
		} else if (row.result.isValid()) {
			statusLabel = new Label("VALID");
			statusLabel.setSclass("cbs-status-badge cbs-badge-valid");
		} else {
			statusLabel = new Label("INVALID");
			statusLabel.setSclass("cbs-status-badge cbs-badge-invalid");
		}
		row.statusLabel = statusLabel;
		Listcell statusCell = new Listcell();
		statusCell.appendChild(statusLabel);
		item.appendChild(statusCell);

		Label reasonLabel = new Label(row.result != null ? nullSafe(row.result.getReason()) : "");
		row.reasonLabel = reasonLabel;
		Listcell reasonCell = new Listcell();
		reasonLabel.setSclass("cbs-reason-label");
		reasonCell.appendChild(reasonLabel);
		item.appendChild(reasonCell);

		return item;
	}

	private String nullSafe(String value) {
		return value != null ? value : "-";
	}

	private static class CbsResultRow {
		final InwardCheque cheque;
		CbsValidationResult result = null;
		String holderName = "-";
		Label statusLabel = null;
		Label reasonLabel = null;

		CbsResultRow(InwardCheque cheque) {
			this.cheque = cheque;
			this.holderName = (cheque.getPayeeName() != null && !cheque.getPayeeName().isBlank())
					? cheque.getPayeeName() : "-";
		}
	}
}