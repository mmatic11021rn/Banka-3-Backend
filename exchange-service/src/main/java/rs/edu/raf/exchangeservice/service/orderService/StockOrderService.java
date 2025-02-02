package rs.edu.raf.exchangeservice.service.orderService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import rs.edu.raf.exchangeservice.client.BankServiceClient;
import rs.edu.raf.exchangeservice.domain.dto.CompanyAccountDto;
import rs.edu.raf.exchangeservice.domain.dto.StockOrderDto;
import rs.edu.raf.exchangeservice.domain.dto.bank.BankTransactionDto;
import rs.edu.raf.exchangeservice.domain.dto.buySell.BuySellStockDto;
import rs.edu.raf.exchangeservice.domain.dto.buySell.BuyStockCompanyDto;
import rs.edu.raf.exchangeservice.domain.dto.buySell.BuyStockUserOTCDto;
import rs.edu.raf.exchangeservice.domain.mappers.StockMapper;
import rs.edu.raf.exchangeservice.domain.model.Actuary;
import rs.edu.raf.exchangeservice.domain.model.enums.BankCertificate;
import rs.edu.raf.exchangeservice.domain.model.enums.OrderStatus;
import rs.edu.raf.exchangeservice.domain.model.enums.OrderType;
import rs.edu.raf.exchangeservice.domain.model.enums.SellerCertificate;
import rs.edu.raf.exchangeservice.domain.model.listing.Stock;
import rs.edu.raf.exchangeservice.domain.model.myListing.Contract;
import rs.edu.raf.exchangeservice.domain.model.order.StockOrder;
import rs.edu.raf.exchangeservice.jacoco.ExcludeFromJacocoGeneratedReport;
import rs.edu.raf.exchangeservice.repository.ActuaryRepository;
import rs.edu.raf.exchangeservice.repository.ContractRepository;
import rs.edu.raf.exchangeservice.repository.listingRepository.StockRepository;
import rs.edu.raf.exchangeservice.repository.orderRepository.StockOrderRepository;
import rs.edu.raf.exchangeservice.service.myListingService.MyStockService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
public class StockOrderService {
    private final StockOrderRepository stockOrderRepository;
    private final StockRepository stockRepository;
    private final ActuaryRepository actuaryRepository;
    private final MyStockService myStockService;
    private final ContractRepository contractRepository;
    private final BankServiceClient bankServiceClient;
    public CopyOnWriteArrayList<StockOrder> ordersToBuy = new CopyOnWriteArrayList<>();

    public List<StockOrder> findAll() {
        return stockOrderRepository.findAll();
    }

    public List<StockOrder> findAllByEmployee(Long id) {
        return stockOrderRepository.findByEmployeeId(id);
    }

    //vracamo sve ordere koje treba approve-ovati
    public List<StockOrder> getAllOrdersToApprove() {
        return stockOrderRepository.findStockOrderByStatus(OrderStatus.WAITING);
    }

    //odobravamo ili ne odobravamo StockOrder Agentu
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public StockOrder approveStockOrder(Long id, boolean approved) {
        StockOrder stockOrder = stockOrderRepository.findByStockOrderId(id);
        if (approved) {
            stockOrder.setStatus(OrderStatus.PROCESSING);
            this.ordersToBuy.add(stockOrder);
        } else {
            stockOrder.setStatus(OrderStatus.REJECTED);
        }
        return stockOrderRepository.save(stockOrder);
    }

    //od buyOrderDto pravimo StockOrder i
    //dodajemo order u listu
    public StockOrderDto buyStock(BuySellStockDto buySellStockDto) {
        StockOrder stockOrder = new StockOrder();
        stockOrder.setEmployeeId(buySellStockDto.getEmployeeId());
        stockOrder.setUserId(buySellStockDto.getUserId());
        stockOrder.setCompanyId(buySellStockDto.getCompanyId());
        stockOrder.setTicker(buySellStockDto.getTicker());
        stockOrder.setAmount(buySellStockDto.getAmount());
        stockOrder.setAmountLeft(buySellStockDto.getAmount());
        stockOrder.setAon(buySellStockDto.isAon());
        stockOrder.setMargin(buySellStockDto.isMargin());

        //TODO: da li staviti ovde jedan if koji proverava da li order ima zaposlenog
        //TODO: u tom slucaju isto bi bilo za banku, user-a i kompaniju

        if(stockOrder.getEmployeeId() != null) {
            if (actuaryRepository.findByEmployeeId(stockOrder.getEmployeeId()).isOrderRequest()) {
                stockOrder.setStatus(OrderStatus.WAITING);
            } else {
                stockOrder.setStatus(OrderStatus.PROCESSING);
            }
        }else {
            stockOrder.setStatus(OrderStatus.PROCESSING);
        }

        if (buySellStockDto.getStopValue() == null)
            stockOrder.setStopValue(0.0);
        else
            stockOrder.setStopValue(buySellStockDto.getStopValue());

        if (buySellStockDto.getLimitValue() == null)
            stockOrder.setLimitValue(0.0);
        else
            stockOrder.setLimitValue(buySellStockDto.getLimitValue());

        //market order
        if (stockOrder.getStopValue() == 0.0 && stockOrder.getLimitValue() == 0.0)
            stockOrder.setType(OrderType.MARKET);

        //stop order
        if (stockOrder.getStopValue() != 0.0 && stockOrder.getLimitValue() == 0.0)
            stockOrder.setType(OrderType.STOP);

        //limit order
        if (stockOrder.getStopValue() == 0.0 && stockOrder.getLimitValue() != 0.0)
            stockOrder.setType(OrderType.LIMIT);

        //stop-limit order
        if (stockOrder.getStopValue() != 0.0 && stockOrder.getLimitValue() != 0.0)
            stockOrder.setType(OrderType.STOP_LIMIT);

        if (stockOrder.getStatus().equals(OrderStatus.PROCESSING)) {
            this.ordersToBuy.add(stockOrderRepository.save(stockOrder));
        }else {
            stockOrderRepository.save(stockOrder);
        }

        return StockMapper.INSTANCE.stockOrderToStockOrderDto(stockOrder);
    }

    //Kompanija salje zahtev za kupovinu. Pravi se ugovor.Druga firma ima pregled pristiglih ugovora i moze da ih prihvati ili odbije.
    public boolean buyCompanyStockOtc(BuyStockCompanyDto buyStockCompanyDto) {
        //TODO: dodati poziv ka bank service-u koji proverava da li company ima dovoljno sredstava da kupi deonice

//        ResponseEntity<?> entity = bankServiceClient.getByCompanyId(buyStockCompanyDto.getBuyerId());
//        CompanyAccountDto companyAccountDto = (CompanyAccountDto) entity.getBody();
//
//        BigDecimal price = buyStockCompanyDto.getPrice();
//        Integer amount = buyStockCompanyDto.getAmount();
//
//        if(companyAccountDto != null && companyAccountDto.getAvailableBalance().compareTo(price.multiply(BigDecimal.valueOf(amount)))<0) {
//            return false; //Firma nema dovoljno sredstava
//        }

        Contract contract = new Contract();
        contract.setCompanyBuyerId(buyStockCompanyDto.getBuyerId());
        contract.setCompanySellerId(buyStockCompanyDto.getSellerId());
        contract.setTicker(buyStockCompanyDto.getTicker());
        contract.setPrice(buyStockCompanyDto.getPrice());
        contract.setAmount(buyStockCompanyDto.getAmount());
        contract.setBankCertificate(BankCertificate.PROCESSING);
        contract.setSellerCertificate(SellerCertificate.PROCESSING);
        contractRepository.save(contract);
        return true;
    }

    public boolean buyUserStockOtc(BuyStockUserOTCDto buyStockUserOTCDto){
        //TODO: dodati poziv ka bank service-u koji proverava da li user ima dovoljno sredstava da kupi deonice

        Contract contract = new Contract();
        contract.setUserBuyerId(buyStockUserOTCDto.getUserBuyerId());
        contract.setUserSellerId(buyStockUserOTCDto.getUserSellerId());
        contract.setTicker(buyStockUserOTCDto.getTicker());
        contract.setPrice(buyStockUserOTCDto.getPrice());
        contract.setAmount(buyStockUserOTCDto.getAmount());
        contract.setBankCertificate(BankCertificate.PROCESSING);
        contract.setSellerCertificate(SellerCertificate.PROCESSING);
        contractRepository.save(contract);
        return true;
    }


    @Scheduled(fixedRate = 10000)
    @ExcludeFromJacocoGeneratedReport
    public void executeTask() {
        if (ordersToBuy.isEmpty()) {
        } else {
            Random rand = new Random();
            int stockNumber = rand.nextInt(ordersToBuy.size());
            StockOrder stockOrder = ordersToBuy.get(stockNumber);   //StockOrder koji obradjujemo

            Stock stock = null;
            Optional<Stock> optionalStock = stockRepository.findByTicker(stockOrder.getTicker());
            if (optionalStock.isPresent())
                stock = optionalStock.get();  //uzimao stock iz baze koji kupujemo
            else {
//                System.out.println("No stocks available with ticker "+stockOrder.getTicker()+", restarting in 15 seconds...");
                return;
            }

            double currentPrice = stock.getAsk();   //trenutna cena po kojoj kupujemo
            int amountToBuy = rand.nextInt(stockOrder.getAmountLeft()) + 1;

            if (stockOrder.isAon()) {
                amountToBuy = stockOrder.getAmount();
            }


            //TODO: isto provera da li order ima zaposlenog ako nema samo se preskoci
            //dodavanje za limit zaposlenog

            //TODO: dodato
            if(stockOrder.getEmployeeId() != null) {
                Actuary actuary = actuaryRepository.findByEmployeeId(stockOrder.getEmployeeId());
                if (actuary.getLimitValue() != 0.0) {
                    if (actuary.getLimitUsed() + (currentPrice * amountToBuy) > actuary.getLimitValue()) {
                        stockOrder.setStatus(OrderStatus.FAILED);
                        ordersToBuy.remove(stockOrder);
                        stockOrderRepository.save(stockOrder);
                        actuaryRepository.save(actuary);
                        return;
                    } else {
                        actuary.setLimitUsed(actuary.getLimitUsed() + (currentPrice * amountToBuy));
                        actuaryRepository.save(actuary);
                    }
                }
            }
            //TODO: ali ovde onda nastaje problem kada se salje dalje na bank service zbog transakcije
            //TODO: odnosno u bankTranasactionDto se salje currencyMark i ne zna se id kompanije ili zaposlenog
            //TODO: resenje mozda da se salje mark, amount, userID i companyID, jedan od njih ce biti null
            //TODO: i u bank service da se proverava koji je null i da se uradi transakcija
            //za bank service da skine pare
            BankTransactionDto bankTransactionDto = new BankTransactionDto();
            bankTransactionDto.setCurrencyMark(stock.getCurrencyMark());

            if (stockOrder.getType().equals(OrderType.MARKET)) {
                bankTransactionDto.setAmount(currentPrice * amountToBuy);
                bankTransactionDto.setUserId(stockOrder.getUserId());
                bankTransactionDto.setCompanyId(stockOrder.getCompanyId());
                bankTransactionDto.setEmployeeId(stockOrder.getEmployeeId());
                //TODO: otkomentarisati naknadno
                bankServiceClient.stockBuyTransaction(bankTransactionDto);

                //dodajemo tranaskciju koju je obavio agent u profitStock
                if(stockOrder.getEmployeeId() != null){
                    myStockService.addProfitForEmployee(stockOrder.getEmployeeId(), currentPrice * amountToBuy * (-1));
                }

                myStockService.addAmountToMyStock(stockOrder.getTicker(), amountToBuy, stockOrder.getUserId(), stockOrder.getCompanyId(), currentPrice);    //dodajemo kolicinu kupljenih deonica u vlasnistvo banke
                stockOrder.setAmountLeft(stockOrder.getAmountLeft() - amountToBuy);
                if (stockOrder.getAmountLeft() <= 0) {
                    stockOrder.setStatus(OrderStatus.FINISHED);
                    ordersToBuy.remove(stockNumber);    //uklanjamo ga iz liste jer je zavrsio
                } else {
                    ordersToBuy.remove(stockNumber);
                    ordersToBuy.add(stockNumber, stockOrder);    //update Objekta u listi
                }
            }

            if (stockOrder.getType().equals(OrderType.LIMIT)) {
                if (currentPrice < stockOrder.getLimitValue()) {
                    bankTransactionDto.setAmount(currentPrice * amountToBuy);
                    bankTransactionDto.setUserId(stockOrder.getUserId());
                    bankTransactionDto.setCompanyId(stockOrder.getCompanyId());
                    bankTransactionDto.setEmployeeId(stockOrder.getEmployeeId());
                    //TODO: otkomentarisati naknadno
                    bankServiceClient.stockBuyTransaction(bankTransactionDto);

                    //dodajemo tranaskciju koju je obavio agent u profitStock
                    if(stockOrder.getEmployeeId() != null){
                        myStockService.addProfitForEmployee(stockOrder.getEmployeeId(), currentPrice * amountToBuy * (-1));
                    }

                    myStockService.addAmountToMyStock(stockOrder.getTicker(), amountToBuy, stockOrder.getUserId(), stockOrder.getCompanyId(), currentPrice);    //dodajemo kolicinu kupljenih deonica u vlasnistvo banke
                    stockOrder.setAmountLeft(stockOrder.getAmountLeft() - amountToBuy);
                    if (stockOrder.getAmountLeft() <= 0) {
                        stockOrder.setStatus(OrderStatus.FINISHED);
                        ordersToBuy.remove(stockNumber);    //uklanjamo ga iz liste jer je zavrsio
                    } else {
                        ordersToBuy.remove(stockNumber);
                        ordersToBuy.add(stockNumber, stockOrder);    //update Objekta u listi
                    }
                } else {
                    stockOrder.setStatus(OrderStatus.FAILED);
                    ordersToBuy.remove(stockNumber);
                }
            }

            if (stockOrder.getType().equals(OrderType.STOP)) {
                if (currentPrice > stockOrder.getStopValue()) {
                    stockOrder.setType(OrderType.MARKET);
                }
            }

            if (stockOrder.getType().equals(OrderType.STOP_LIMIT)) {
                if (currentPrice > stockOrder.getStopValue()) {
                    stockOrder.setType(OrderType.LIMIT);
                }
            }

            this.stockOrderRepository.save(stockOrder); //cuvamo promenjene vrednosti u bazi
        }
    }
}
