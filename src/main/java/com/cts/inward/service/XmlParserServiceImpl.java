// XmlParserServiceImpl.java — StAX version
package com.cts.inward.service;

import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import com.cts.inward.dto.ChequeDTO;

public class XmlParserServiceImpl implements XmlParserService {

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy")
    };

    @Override
    public List<ChequeDTO> parseXml(File xmlFile) {

        List<ChequeDTO> chequeList = new ArrayList<>();

        try {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            XMLStreamReader reader = factory.createXMLStreamReader(new FileInputStream(xmlFile));

            String batchId = "";
            ChequeDTO currentDto = null;
            String currentTag = "";

            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    currentTag = reader.getLocalName();

                    if (currentTag.equals("cheque")) {
                        // Start of a new cheque record
                        currentDto = new ChequeDTO();
                        currentDto.setBatchId(batchId);
                    }

                } else if (event == XMLStreamConstants.CHARACTERS) {
                    String text = reader.getText().trim();
                    if (text.isEmpty()) continue;

                    // Batch-level field
                    if (currentTag.equals("batchId")) {
                        batchId = text;

                    // Cheque-level fields
                    } else if (currentDto != null) {
                        switch (currentTag) {
                            case "chequeNumber":    currentDto.setChequeNumber(text);    break;
                            case "accountNumber":   currentDto.setAccountNumber(text);   break;
                            case "drawerName":      currentDto.setDrawerName(text);      break;
                            case "payeeName":       currentDto.setPayeeName(text);       break;
                            case "micrCode":        currentDto.setMicrCode(text);        break;
                            case "transactionCode": currentDto.setTransactionCode(text); break;
                            case "branchName":      currentDto.setBranchName(text);      break;
                            case "presentingBank":  currentDto.setPresentingBank(text);  break;
                            case "frontImage":      currentDto.setFrontImage(text);      break;
                            case "backImage":       currentDto.setBackImage(text);       break;
                            case "amountInWords":   currentDto.setAmountInWords(text);   break;
                            case "ifscCode":        currentDto.setIfscCode(text);        break;
                            case "amount":
                                currentDto.setAmount(Double.parseDouble(text));
                                break;
                            case "chequeDate":
                                currentDto.setChequeDate(parseDate(text));
                                break;
                        }
                    }

                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    // When </cheque> closes, save the DTO
                    if (reader.getLocalName().equals("cheque") && currentDto != null) {
                        chequeList.add(currentDto);
                        currentDto = null;
                    }
                    currentTag = "";
                }
            }

            reader.close();
            System.out.println("Total Cheques Parsed : " + chequeList.size());

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to parse XML file : " + e.getMessage(), e);
        }

        return chequeList;
    }

    // Same date parsing helper — no change needed
    private LocalDateTime parseDate(String value) {
        if (value == null || value.isEmpty()) return null;

        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDateTime.parse(value, fmt);
            } catch (DateTimeParseException ignored) {}
        }

        try {
            return LocalDateTime.parse(value + " 00:00:00",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withResolverStyle(ResolverStyle.LENIENT));
        } catch (DateTimeParseException ignored) {}

        System.err.println("Warning : Could not parse date value: " + value);
        return null;
    }
}