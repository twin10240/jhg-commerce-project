package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.ReturnPort;
import com.jhg.hgpage.contract.ReturnPort.CreateItem;
import com.jhg.hgpage.contract.ReturnPort.CreateRequest;
import com.jhg.hgpage.contract.ReturnPort.PermanentReturnRejection;
import com.jhg.hgpage.contract.ReturnPort.ReturnAuthenticationFailure;
import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.contract.ReturnPort.TransientReturnFailure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnSubmissionService {

    private final CustomerReturnService customerReturnService;
    private final ReturnSyncService returnSyncService;
    private final ReturnPort returnPort;

    public void submit(Long returnId) {
        CustomerReturnService.Submission submission = customerReturnService.pendingSubmission(returnId);
        try {
            ReturnResult result = returnPort.create(toRequest(submission));
            if (!submission.requestKey().equals(result.requestKey())) {
                throw new ReturnSyncService.ReturnContractMismatchException();
            }
            returnSyncService.apply(result);
        } catch (PermanentReturnRejection exception) {
            customerReturnService.markSubmissionFailed(returnId, exception.code());
        } catch (TransientReturnFailure | ReturnAuthenticationFailure exception) {
            log.warn("RMA 접수 보류: returnId={}, orderId={}", returnId, submission.orderId());
        }
    }

    private CreateRequest toRequest(CustomerReturnService.Submission submission) {
        return new CreateRequest(submission.requestKey(), submission.orderId(), submission.reason(),
                submission.items().stream()
                        .map(item -> new CreateItem(item.orderItemId(), item.productId(), item.quantity()))
                        .toList());
    }
}
