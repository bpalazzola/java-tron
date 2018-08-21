package org.tron.core.actuator;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ExchangeCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.config.Parameter.ChainParameters;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.protos.Contract.ExchangeInjectContract;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class ExchangeInjectActuator extends AbstractActuator {

  ExchangeInjectActuator(final Any contract, final Manager dbManager) {
    super(contract, dbManager);
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    long fee = calcFee();
    try {
      final ExchangeInjectContract exchangeInjectContract = this.contract
          .unpack(ExchangeInjectContract.class);
      AccountCapsule accountCapsule = dbManager.getAccountStore()
          .get(exchangeInjectContract.getOwnerAddress().toByteArray());

      ExchangeCapsule exchangeCapsule = dbManager.getExchangeStore().
            get(ByteArray.fromLong(exchangeInjectContract.getExchangeId()));

      byte[] firstTokenID = exchangeCapsule.getFirstTokenId();
      byte[] secondTokenID = exchangeCapsule.getSecondTokenId();
      long firstTokenBalance = exchangeCapsule.getFirstTokenBalance();
      long secondTokenBalance = exchangeCapsule.getSecondTokenBalance();

      byte[] tokenID = exchangeInjectContract.getTokenId().toByteArray();
      long tokenQuant = exchangeInjectContract.getQuant();

      byte[] anotherTokenID;
      long anotherTokenQuant;

      double ratio;
      if (Arrays.equals(tokenID, firstTokenID)) {
        anotherTokenID = secondTokenID;
        ratio = (double) secondTokenBalance / firstTokenBalance;
      } else {
        anotherTokenID = firstTokenID;
        ratio = (double) firstTokenBalance / secondTokenBalance;
      }
      anotherTokenQuant = (long) ratio * tokenQuant;

      long newBalance = accountCapsule.getBalance() - calcFee();

      if (tokenID == "_".getBytes()) {
        accountCapsule.setBalance(newBalance - tokenQuant);
      } else {
        accountCapsule.reduceAssetAmount(tokenID, tokenQuant);
      }

      if (anotherTokenID == "_".getBytes()) {
        accountCapsule.setBalance(newBalance - anotherTokenQuant);
      } else {
        accountCapsule.reduceAssetAmount(anotherTokenID, anotherTokenQuant);
      }

      dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
      dbManager.getExchangeStore().put(exchangeCapsule.createDbKey(), exchangeCapsule);

      ret.setStatus(fee, code.SUCESS);
    } catch (ItemNotFoundException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }
    return true;
  }


  @Override
  public boolean validate() throws ContractValidateException {
    if (this.contract == null) {
      throw new ContractValidateException("No contract!");
    }
    if (this.dbManager == null) {
      throw new ContractValidateException("No dbManager!");
    }
    if (!this.contract.is(ExchangeInjectContract.class)) {
      throw new ContractValidateException(
          "contract type error,expected type [ExchangeInjectContract],real type[" + contract
              .getClass() + "]");
    }
    final ExchangeInjectContract contract;
    try {
      contract = this.contract.unpack(ExchangeInjectContract.class);
    } catch (InvalidProtocolBufferException e) {
      throw new ContractValidateException(e.getMessage());
    }

    byte[] ownerAddress = contract.getOwnerAddress().toByteArray();
    String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);

    if (!Wallet.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid address");
    }

    if (!this.dbManager.getAccountStore().has(ownerAddress)) {
      throw new ContractValidateException("account[" + readableOwnerAddress + "] not exists");
    }

    AccountCapsule accountCapsule = this.dbManager.getAccountStore().get(ownerAddress);

    if (accountCapsule.getBalance() < calcFee()) {
      throw new ContractValidateException("No enough balance for exchange inject fee!");
    }

    ExchangeCapsule exchangeCapsule;
    try {
      exchangeCapsule = dbManager.getExchangeStore().
          get(ByteArray.fromLong(contract.getExchangeId()));
    } catch (ItemNotFoundException ex) {
      throw new ContractValidateException("Exchange[" + contract.getExchangeId() + "] not exists");
    }

    if (!accountCapsule.getAddress().equals(exchangeCapsule.getCreatorAddress())) {
      throw new ContractValidateException("account[" + readableOwnerAddress + "] is not creator");
    }

    byte[] firstTokenID = exchangeCapsule.getFirstTokenId();
    byte[] secondTokenID = exchangeCapsule.getSecondTokenId();
    long firstTokenBalance = exchangeCapsule.getFirstTokenBalance();
    long secondTokenBalance = exchangeCapsule.getSecondTokenBalance();

    byte[] tokenID = contract.getTokenId().toByteArray();
    long tokenQuant = contract.getQuant();

    byte[] anotherTokenID;
    long anotherTokenQuant;

    if (!Arrays.equals(tokenID, firstTokenID) && !Arrays.equals(tokenID, secondTokenID)) {
      throw new ContractValidateException("token is not in exchange");
    }

    if (tokenQuant <= 0) {
      throw new ContractValidateException("injected token balance must greater than zero");
    }

    double ratio;
    if (Arrays.equals(tokenID, firstTokenID)) {
      anotherTokenID = secondTokenID;
      ratio = (double) secondTokenBalance / firstTokenBalance;
    } else {
      anotherTokenID = firstTokenID;
      ratio = (double) firstTokenBalance / secondTokenBalance;
    }
    anotherTokenQuant = (long) ratio * tokenQuant;

    if (tokenID == "_".getBytes()) {
      if (accountCapsule.getBalance() < (tokenQuant + calcFee())) {
        throw new ContractValidateException("balance is not enough");
      }
    } else {
      if (!accountCapsule.assetBalanceEnough(tokenID, tokenQuant)) {
        throw new ContractValidateException("token balance is not enough");
      }
    }

    if (anotherTokenID == "_".getBytes()) {
      if (accountCapsule.getBalance() < (anotherTokenQuant + calcFee())) {
        throw new ContractValidateException("balance is not enough");
      }
    } else {
      if (!accountCapsule.assetBalanceEnough(anotherTokenID, anotherTokenQuant)) {
        throw new ContractValidateException("another token balance is not enough");
      }
    }

    return true;
  }


  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(ExchangeInjectContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

  private boolean validKey(long idx) {
    return idx >= 0 && idx < ChainParameters.values().length;
  }

}