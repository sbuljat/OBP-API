package code.api.v1_2_1

import code.api.util.APIUtil
import code.api.v1_2_1.SuccessMessage
import net.liftweb.http.{JsonResponse, Req}
import net.liftweb.json.Extraction
import net.liftweb.common._
import code.model._
import net.liftweb.json.JsonAST.JValue
import APIUtil._
import net.liftweb.util.Helpers._
import net.liftweb.http.rest.RestHelper
import java.net.URL
import net.liftweb.util.Props
import code.bankconnectors._
import code.bankconnectors.OBPOffset
import code.bankconnectors.OBPFromDate
import code.bankconnectors.OBPToDate
import code.model.ViewCreationJSON
import net.liftweb.common.Full
import code.model.ViewUpdateData

import scala.collection.immutable.Nil
import scala.collection.mutable.ArrayBuffer
// Makes JValue assignment to Nil work
import net.liftweb.json.JsonDSL._

case class MakePaymentJson(bank_id : String, account_id : String, amount : String)

trait APIMethods121 {
  //needs to be a RestHelper to get access to JsonGet, JsonPost, etc.
  self: RestHelper =>

  // helper methods begin here

  private def bankAccountsListToJson(bankAccounts: List[BankAccount], user : Box[User]): JValue = {
    val accJson : List[AccountJSON] = bankAccounts.map( account => {
      val views = account permittedViews user
      val viewsAvailable : List[ViewJSON] =
        views.map( v => {
          JSONFactory.createViewJSON(v)
        })
      JSONFactory.createAccountJSON(account,viewsAvailable)
    })

    val accounts = new AccountsJSON(accJson)
    Extraction.decompose(accounts)
  }

  def checkIfLocationPossible(lat:Double,lon:Double) : Box[Unit] = {
    if(scala.math.abs(lat) <= 90 & scala.math.abs(lon) <= 180)
      Full()
    else
      Failure("Coordinates not possible")
  }

  private def moderatedTransactionMetadata(bankId : BankId, accountId : AccountId, viewId : ViewId, transactionID : TransactionId, user : Box[User]) : Box[ModeratedTransactionMetadata] ={
    for {
      account <- BankAccount(bankId, accountId)
      view <- View.fromUrl(viewId, account)
      moderatedTransaction <- account.moderatedTransaction(transactionID, view, user)
      metadata <- Box(moderatedTransaction.metadata) ?~ {"view " + viewId + " does not authorize metadata access"}
    } yield metadata
  }

  // helper methods end here

  val Implementations1_2_1 = new Object(){

    val resourceDocs = ArrayBuffer[ResourceDoc]()
    val emptyObjectJson : JValue = Nil
    val apiVersion : String = "1_2_1"


    resourceDocs += ResourceDoc(
      apiVersion,
      "root",
      "GET",
      "/",
      "Returns API version, git commit, hosted by etc.",
      emptyObjectJson,
      emptyObjectJson)

    def root(apiVersion : String) : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      case Nil JsonGet json => {
        user =>
          val apiDetails: JValue = {
            val hostedBy = new HostedBy("TESOBE", "contact@tesobe.com", "+49 (0)30 8145 3994")
            val apiInfoJSON = new APIInfoJSON(apiVersion, gitCommit, hostedBy)
            Extraction.decompose(apiInfoJSON)
          }

          Full(successJsonResponse(apiDetails, 200))
      }
    }


    resourceDocs += ResourceDoc(
      apiVersion,
      "allBanks",
      "GET",
      "/banks",
      "Returns all banks available on this API instance",
      emptyObjectJson,
      emptyObjectJson)

    lazy val allBanks : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get banks
      case "banks" :: Nil JsonGet json => {
        user =>
          def banksToJson(banksList: List[Bank]): JValue = {
            val banksJSON: List[BankJSON] = banksList.map(b => {
              JSONFactory.createBankJSON(b)
            })
            val banks = new BanksJSON(banksJSON)
            Extraction.decompose(banks)
          }

          Full(successJsonResponse(banksToJson(Bank.all)))
      }
    }


    resourceDocs += ResourceDoc(
      apiVersion,
      "bankById",
      "GET",
      "/banks/BANK_ID",
      "Returns the bank specified by BANK_ID",
      emptyObjectJson,
      emptyObjectJson)


    lazy val bankById : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get bank by id
      case "banks" :: BankId(bankId) :: Nil JsonGet json => {
        user =>
          def bankToJson(bank : Bank) : JValue = {
            val bankJSON = JSONFactory.createBankJSON(bank)
            Extraction.decompose(bankJSON)
          }
          for(bank <- Bank(bankId))
          yield successJsonResponse(bankToJson(bank))
      }
    }


    resourceDocs += ResourceDoc(
      apiVersion,
      "allAccountsAllBanks",
      "GET",
      "/accounts",
      "Get accounts for all banks (private + public)",
      emptyObjectJson,
      emptyObjectJson)

    lazy val allAccountsAllBanks : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get accounts for all banks (private + public)
      case "accounts" :: Nil JsonGet json => {
        user =>
          Full(successJsonResponse(bankAccountsListToJson(BankAccount.accounts(user), user)))
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "privateAccountsAllBanks",
      "GET",
      "/accounts/private",
      "Get private accounts for all banks.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val privateAccountsAllBanks : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get private accounts for all banks
      case "accounts" :: "private" :: Nil JsonGet json => {
        user =>
          for {
            u <- user ?~ "user not found"
          } yield {
            val availableAccounts = BankAccount.nonPublicAccounts(u)
            successJsonResponse(bankAccountsListToJson(availableAccounts, Full(u)))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "publicAccountsAllBanks",
      "GET",
      "/accounts/public",
      "Get public accounts for all banks.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val publicAccountsAllBanks : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get public accounts for all banks
      case "accounts" :: "public" :: Nil JsonGet json => {
        user =>
          val publicAccountsJson = bankAccountsListToJson(BankAccount.publicAccounts, Empty)
          Full(successJsonResponse(publicAccountsJson))
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "allAccountsAtOneBank",
      "GET",
      "/banks/BANK_ID/accounts",
      "Get accounts for a single bank (private + public).",
      emptyObjectJson,
      emptyObjectJson)

    lazy val allAccountsAtOneBank : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get accounts for a single bank (private + public)
      case "banks" :: BankId(bankId) :: "accounts" :: Nil JsonGet json => {
        user =>
          for{
            bank <- Bank(bankId)
          } yield {
            val availableAccounts = bank.accounts(user)
            successJsonResponse(bankAccountsListToJson(availableAccounts, user))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "privateAccountsAtOneBank",
      "GET",
      "/banks/BANK_ID/accounts/private",
      "Get private accounts for a single bank.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val privateAccountsAtOneBank : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get private accounts for a single bank
      case "banks" :: BankId(bankId) :: "accounts" :: "private" :: Nil JsonGet json => {
        user =>
          for {
            u <- user ?~ "user not found"
            bank <- Bank(bankId)
          } yield {
            val availableAccounts = bank.nonPublicAccounts(u)
            successJsonResponse(bankAccountsListToJson(availableAccounts, Full(u)))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "publicAccountsAtOneBank",
      "GET",
      "/banks/BANK_ID/accounts/public",
      "Get public accounts for a single bank.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val publicAccountsAtOneBank : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get public accounts for a single bank
      case "banks" :: BankId(bankId) :: "accounts" :: "public" :: Nil JsonGet json => {
        user =>
          for {
            bank <- Bank(bankId)
          } yield {
            val publicAccountsJson = bankAccountsListToJson(bank.publicAccounts, Empty)
            successJsonResponse(publicAccountsJson)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "accountById",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/account",
      "Get account by id.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val accountById : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get account by id
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "account" :: Nil JsonGet json => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            availableviews <- Full(account.permittedViews(user))
            view <- View.fromUrl(viewId, account)
            moderatedAccount <- account.moderatedBankAccount(view, user)
          } yield {
            val viewsAvailable = availableviews.map(JSONFactory.createViewJSON)
            val moderatedAccountJson = JSONFactory.createBankAccountJSON(moderatedAccount, viewsAvailable)
            successJsonResponse(Extraction.decompose(moderatedAccountJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateAccountLabel",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID",
      "Change account label.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateAccountLabel : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //change account label
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: Nil JsonPost json -> _ => {
        user =>
          for {
            u <- user ?~ "user not found"
            json <- tryo { json.extract[UpdateAccountJSON] } ?~ "wrong JSON format"
            account <- BankAccount(bankId, accountId)
          } yield {
            account.updateLabel(u, json.label)
            successJsonResponse(Extraction.decompose(SuccessMessage("ok")), 200)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getViewsForBankAccount",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/views",
      "Get the available views on an bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getViewsForBankAccount : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get the available views on an bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "views" :: Nil JsonGet json => {
        user =>
          for {
            u <- user ?~ "user not found"
            account <- BankAccount(bankId, accountId)
            views <- account views u  // In other words: views = account.views(u) This calls BankingData.scala BankAccount.views
          } yield {
            val viewsJSON = JSONFactory.createViewsJSON(views)
            successJsonResponse(Extraction.decompose(viewsJSON))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "createViewForBankAccount",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/views",
      "Creates a view on an bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val createViewForBankAccount : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //creates a view on an bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "views" :: Nil JsonPost json -> _ => {
        user =>
          for {
            u <- user ?~ "user not found"
            json <- tryo{json.extract[ViewCreationJSON]} ?~ "wrong JSON format"
            account <- BankAccount(bankId, accountId)
            view <- account createView (u, json)
          } yield {
            val viewJSON = JSONFactory.createViewJSON(view)
            successJsonResponse(Extraction.decompose(viewJSON), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateViewForBankAccount",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/views/VIEW_ID",
      "Updates a view on a bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateViewForBankAccount : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //updates a view on a bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "views" :: ViewId(viewId) :: Nil JsonPut json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            u <- user ?~ "user not found"
            updateJson <- tryo{json.extract[ViewUpdateData]} ?~ "wrong JSON format"
            updatedView <- account.updateView(u, viewId, updateJson)
          } yield {
            val viewJSON = JSONFactory.createViewJSON(updatedView)
            successJsonResponse(Extraction.decompose(viewJSON), 200)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteViewForBankAccount",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/views/VIEW_ID",
      "Deletes a view on an bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteViewForBankAccount : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //deletes a view on an bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "views" :: ViewId(viewId) :: Nil JsonDelete json => {
        user =>
          for {
            u <- user ?~ "user not found"
            account <- BankAccount(bankId, accountId)
            view <- account removeView (u, viewId)
          } yield noContentJsonResponse
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getPermissionsForBankAccount",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/permissions",
      "Get access.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getPermissionsForBankAccount : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get access
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "permissions" :: Nil JsonGet json => {
        user =>
          for {
            u <- user ?~ "user not found"
            account <- BankAccount(bankId, accountId)
            permissions <- account permissions u
          } yield {
            val permissionsJSON = JSONFactory.createPermissionsJSON(permissions)
            successJsonResponse(Extraction.decompose(permissionsJSON))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getPermissionForUserForBankAccount",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/permissions/PROVIDER_ID/USER_ID",
      "Get access for specific user.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getPermissionForUserForBankAccount : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get access for specific user
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "permissions" :: providerId :: userId :: Nil JsonGet json => {
        user =>
          for {
            u <- user ?~ "user not found"
            account <- BankAccount(bankId, accountId)
            permission <- account permission(u, providerId, userId)
          } yield {
            val views = JSONFactory.createViewsJSON(permission.views)
            successJsonResponse(Extraction.decompose(views))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "addPermissionForUserForBankAccountForMultipleViews",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/permissions/PROVIDER_ID/USER_ID/views",
      "Add access for specific user to a list of views.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addPermissionForUserForBankAccountForMultipleViews : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add access for specific user to a list of views
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "permissions" :: providerId :: userId :: "views" :: Nil JsonPost json -> _ => {
        user =>
          for {
            u <- user ?~ "user not found"
            account <- BankAccount(bankId, accountId)
            viewIds <- tryo{json.extract[ViewIdsJson]} ?~ "wrong format JSON"
            addedViews <- account addPermissions(u, viewIds.views.map(viewIdString => ViewUID(ViewId(viewIdString), bankId, accountId)), providerId, userId)
          } yield {
            val viewJson = JSONFactory.createViewsJSON(addedViews)
            successJsonResponse(Extraction.decompose(viewJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "addPermissionForUserForBankAccountForOneView",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/permissions/PROVIDER_ID/USER_ID/views/VIEW_ID",
      "Add access for specific user to a specific view.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addPermissionForUserForBankAccountForOneView : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add access for specific user to a specific view
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "permissions" :: providerId :: userId :: "views" :: ViewId(viewId) :: Nil JsonPost json -> _ => {
        user =>
          for {
            u <- user ?~ "user not found"
            account <- BankAccount(bankId, accountId)
            addedView <- account addPermission(u, ViewUID(viewId, bankId, accountId), providerId, userId)
          } yield {
            val viewJson = JSONFactory.createViewJSON(addedView)
            successJsonResponse(Extraction.decompose(viewJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "removePermissionForUserForBankAccountForOneView",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/permissions/PROVIDER_ID/USER_ID/views/VIEW_ID",
      "Delete access for specific user to one view.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val removePermissionForUserForBankAccountForOneView : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete access for specific user to one view
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "permissions" :: providerId :: userId :: "views" :: ViewId(viewId) :: Nil JsonDelete json => {
        user =>
          for {
            u <- user ?~ "user not found"
            account <- BankAccount(bankId, accountId)
            isRevoked <- account revokePermission(u, ViewUID(viewId, bankId, accountId), providerId, userId)
            if(isRevoked)
          } yield noContentJsonResponse
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "removePermissionForUserForBankAccountForAllViews",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/permissions/PROVIDER_ID/USER_ID/views",
      "Delete access for specific user to all the views.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val removePermissionForUserForBankAccountForAllViews : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete access for specific user to all the views
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: "permissions" :: providerId :: userId :: "views" :: Nil JsonDelete json => {
        user =>
          for {
            u <- user ?~ "user not found"
            account <- BankAccount(bankId, accountId)
            isRevoked <- account revokeAllPermissions(u, providerId, userId)
            if(isRevoked)
          } yield noContentJsonResponse
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getCounterpartiesForBankAccount",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts",
      "Get other accounts for one account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getCounterpartiesForBankAccount : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get other accounts for one account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts" :: Nil JsonGet json => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccounts <- account.moderatedOtherBankAccounts(view, user)
          } yield {
            val otherBankAccountsJson = JSONFactory.createOtherBankAccountsJSON(otherBankAccounts)
            successJsonResponse(Extraction.decompose(otherBankAccountsJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getCounterpartyByIdForBankAccount",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID",
      "Get one other account by id.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getCounterpartyByIdForBankAccount : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get one other account by id
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: Nil JsonGet json => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
          } yield {
            val otherBankAccountJson = JSONFactory.createOtherBankAccount(otherBankAccount)
            successJsonResponse(Extraction.decompose(otherBankAccountJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getCounterpartyMetadata",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/metadata",
      "Get metadata of one other account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getCounterpartyMetadata : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get metadata of one other account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "metadata" :: Nil JsonGet json => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
          } yield {
            val metadataJson = JSONFactory.createOtherAccountMetaDataJSON(metadata)
            successJsonResponse(Extraction.decompose(metadataJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getCounterpartyPublicAlias",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/public_alias",
      "Get public alias of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getCounterpartyPublicAlias : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get public alias of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "public_alias" :: Nil JsonGet json => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            alias <- Box(metadata.publicAlias) ?~ {"the view " + viewId + "does not allow public alias access"}
          } yield {
            val aliasJson = JSONFactory.createAliasJSON(alias)
            successJsonResponse(Extraction.decompose(aliasJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "addCounterpartyPublicAlias",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/public_alias",
      "Add public alias to other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addCounterpartyPublicAlias : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add public alias to other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "public_alias" :: Nil JsonPost json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addAlias <- Box(metadata.addPublicAlias) ?~ {"the view " + viewId + "does not allow adding a public alias"}
            aliasJson <- tryo{(json.extract[AliasJSON])} ?~ {"wrong JSON format"}
            if(addAlias(aliasJson.alias))
          } yield {
            successJsonResponse(Extraction.decompose(SuccessMessage("public alias added")), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateCounterpartyPublicAlias",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/public_alias",
      "Update public alias of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateCounterpartyPublicAlias : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //update public alias of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "public_alias" :: Nil JsonPut json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addAlias <- Box(metadata.addPublicAlias) ?~ {"the view " + viewId + "does not allow updating the public alias"}
            aliasJson <- tryo{(json.extract[AliasJSON])} ?~ {"wrong JSON format"}
            if(addAlias(aliasJson.alias))
          } yield {
            successJsonResponse(Extraction.decompose(SuccessMessage("public alias updated")))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteCounterpartyPublicAlias",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/public_alias",
      "Delete public alias of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteCounterpartyPublicAlias : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete public alias of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "public_alias" :: Nil JsonDelete _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addAlias <- Box(metadata.addPublicAlias) ?~ {"the view " + viewId + "does not allow deleting the public alias"}
            if(addAlias(""))
          } yield noContentJsonResponse
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getCounterpartyPrivateAlias",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/private_alias",
      "Get private alias of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getCounterpartyPrivateAlias : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get private alias of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "private_alias" :: Nil JsonGet json => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            alias <- Box(metadata.privateAlias) ?~ {"the view " + viewId + "does not allow private alias access"}
          } yield {
            val aliasJson = JSONFactory.createAliasJSON(alias)
            successJsonResponse(Extraction.decompose(aliasJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "addCounterpartyPrivateAlias",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/private_alias",
      "Add private alias to other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addCounterpartyPrivateAlias : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add private alias to other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "private_alias" :: Nil JsonPost json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addAlias <- Box(metadata.addPrivateAlias) ?~ {"the view " + viewId + "does not allow adding a private alias"}
            aliasJson <- tryo{(json.extract[AliasJSON])} ?~ {"wrong JSON format"}
            if(addAlias(aliasJson.alias))
          } yield {
            val successJson = SuccessMessage("private alias added")
            successJsonResponse(Extraction.decompose(successJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateCounterpartyPrivateAlias",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/private_alias",
      "Update private alias of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateCounterpartyPrivateAlias : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //update private alias of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "private_alias" :: Nil JsonPut json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addAlias <- Box(metadata.addPrivateAlias) ?~ {"the view " + viewId + "does not allow updating the private alias"}
            aliasJson <- tryo{(json.extract[AliasJSON])} ?~ {"wrong JSON format"}
            if(addAlias(aliasJson.alias))
          } yield {
            val successJson = SuccessMessage("private alias updated")
            successJsonResponse(Extraction.decompose(successJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteCounterpartyPrivateAlias",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/private_alias",
      "Delete private alias of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteCounterpartyPrivateAlias : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete private alias of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "private_alias" :: Nil JsonDelete _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addAlias <- Box(metadata.addPrivateAlias) ?~ {"the view " + viewId + "does not allow deleting the private alias"}
            if(addAlias(""))
          } yield noContentJsonResponse
      }
    }

    //TODO: get more info of counterparty?

    resourceDocs += ResourceDoc(
      apiVersion,
      "addCounterpartyMoreInfo",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/more_info",
      "Add more info to other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addCounterpartyMoreInfo : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add more info to other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "more_info" :: Nil JsonPost json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addMoreInfo <- Box(metadata.addMoreInfo) ?~ {"the view " + viewId + "does not allow adding more info"}
            moreInfoJson <- tryo{(json.extract[MoreInfoJSON])} ?~ {"wrong JSON format"}
            if(addMoreInfo(moreInfoJson.more_info))
          } yield {
            val successJson = SuccessMessage("more info added")
            successJsonResponse(Extraction.decompose(successJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateCounterpartyMoreInfo",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/more_info",
      "update more info of other bank account",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateCounterpartyMoreInfo : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //update more info of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "more_info" :: Nil JsonPut json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addMoreInfo <- Box(metadata.addMoreInfo) ?~ {"the view " + viewId + "does not allow updating more info"}
            moreInfoJson <- tryo{(json.extract[MoreInfoJSON])} ?~ {"wrong JSON format"}
            if(addMoreInfo(moreInfoJson.more_info))
          } yield {
            val successJson = SuccessMessage("more info updated")
            successJsonResponse(Extraction.decompose(successJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteCounterpartyMoreInfo",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/more_info",
      "Delete more info of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteCounterpartyMoreInfo : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete more info of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "more_info" :: Nil JsonDelete _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addMoreInfo <- Box(metadata.addMoreInfo) ?~ {"the view " + viewId + "does not allow deleting more info"}
            if(addMoreInfo(""))
          } yield noContentJsonResponse
      }
    }

    //TODO: get url of counterparty?

    resourceDocs += ResourceDoc(
      apiVersion,
      "addCounterpartyUrl",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/url",
      "Add url to other bank account.",
      emptyObjectJson,
      emptyObjectJson)


    lazy val addCounterpartyUrl : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add url to other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "url" :: Nil JsonPost json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addUrl <- Box(metadata.addURL) ?~ {"the view " + viewId + "does not allow adding a url"}
            urlJson <- tryo{(json.extract[UrlJSON])} ?~ {"wrong JSON format"}
            if(addUrl(urlJson.URL))
          } yield {
            val successJson = SuccessMessage("url added")
            successJsonResponse(Extraction.decompose(successJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateCounterpartyUrl",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/url",
      "Update url of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateCounterpartyUrl : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //update url of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "url" :: Nil JsonPut json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addUrl <- Box(metadata.addURL) ?~ {"the view " + viewId + "does not allow updating a url"}
            urlJson <- tryo{(json.extract[UrlJSON])} ?~ {"wrong JSON format"}
            if(addUrl(urlJson.URL))
          } yield {
            val successJson = SuccessMessage("url updated")
            successJsonResponse(Extraction.decompose(successJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteCounterpartyUrl",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/url",
      "Delete url of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteCounterpartyUrl : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete url of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "url" :: Nil JsonDelete _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addUrl <- Box(metadata.addURL) ?~ {"the view " + viewId + "does not allow deleting a url"}
            if(addUrl(""))
          } yield noContentJsonResponse
      }
    }

    //TODO: get image url of counterparty?

    resourceDocs += ResourceDoc(
      apiVersion,
      "addCounterpartyImageUrl",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/image_url",
      "Add image url to other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addCounterpartyImageUrl : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add image url to other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "image_url" :: Nil JsonPost json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addImageUrl <- Box(metadata.addImageURL) ?~ {"the view " + viewId + "does not allow adding an image url"}
            imageUrlJson <- tryo{(json.extract[ImageUrlJSON])} ?~ {"wrong JSON format"}
            if(addImageUrl(imageUrlJson.image_URL))
          } yield {
            val successJson = SuccessMessage("image url added")
            successJsonResponse(Extraction.decompose(successJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateCounterpartyImageUrl",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/image_url",
      "Update image url of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateCounterpartyImageUrl : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //update image url of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "image_url" :: Nil JsonPut json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addImageUrl <- Box(metadata.addImageURL) ?~ {"the view " + viewId + "does not allow updating an image url"}
            imageUrlJson <- tryo{(json.extract[ImageUrlJSON])} ?~ {"wrong JSON format"}
            if(addImageUrl(imageUrlJson.image_URL))
          } yield {
            val successJson = SuccessMessage("image url updated")
            successJsonResponse(Extraction.decompose(successJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteCounterpartyImageUrl",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/image_url",
      "Delete image url of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteCounterpartyImageUrl : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete image url of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "image_url" :: Nil JsonDelete _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addImageUrl <- Box(metadata.addImageURL) ?~ {"the view " + viewId + "does not allow deleting an image url"}
            if(addImageUrl(""))
          } yield noContentJsonResponse
      }
    }

    //TODO: get open corporates url of counterparty?

    resourceDocs += ResourceDoc(
      apiVersion,
      "addCounterpartyOpenCorporatesUrl",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/open_corporates_url",
      "Add open corporate url to other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addCounterpartyOpenCorporatesUrl : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add open corporate url to other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "open_corporates_url" :: Nil JsonPost json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addOpenCorpUrl <- Box(metadata.addOpenCorporatesURL) ?~ {"the view " + viewId + "does not allow adding an open corporate url"}
            opernCoprUrl <- tryo{(json.extract[OpenCorporateUrlJSON])} ?~ {"wrong JSON format"}
            if(addOpenCorpUrl(opernCoprUrl.open_corporates_URL))
          } yield {
            val successJson = SuccessMessage("open corporate url added")
            successJsonResponse(Extraction.decompose(successJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateCounterpartyOpenCorporatesUrl",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/open_corporates_url",
      "Update open corporate url of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateCounterpartyOpenCorporatesUrl : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //update open corporate url of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "open_corporates_url" :: Nil JsonPut json -> _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addOpenCorpUrl <- Box(metadata.addOpenCorporatesURL) ?~ {"the view " + viewId + "does not allow updating an open corporate url"}
            opernCoprUrl <- tryo{(json.extract[OpenCorporateUrlJSON])} ?~ {"wrong JSON format"}
            if(addOpenCorpUrl(opernCoprUrl.open_corporates_URL))
          } yield {
            val successJson = SuccessMessage("open corporate url updated")
            successJsonResponse(Extraction.decompose(successJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteCounterpartyOpenCorporatesUrl",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/open_corporates_url",
      "Delete open corporate url of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteCounterpartyOpenCorporatesUrl : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete open corporate url of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "open_corporates_url" :: Nil JsonDelete _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addOpenCorpUrl <- Box(metadata.addOpenCorporatesURL) ?~ {"the view " + viewId + "does not allow deleting an open corporate url"}
            if(addOpenCorpUrl(""))
          } yield noContentJsonResponse
      }
    }

    //TODO: get corporate location of counterparty?

    resourceDocs += ResourceDoc(
      apiVersion,
      "addCounterpartyCorporateLocation",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/corporate_location",
      "Add corporate location to other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addCounterpartyCorporateLocation : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add corporate location to other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts" :: other_account_id :: "corporate_location" :: Nil JsonPost json -> _ => {
        user =>
          for {
            u <- user
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addCorpLocation <- Box(metadata.addCorporateLocation) ?~ {"the view " + viewId + "does not allow adding a corporate location"}
            corpLocationJson <- tryo{(json.extract[CorporateLocationJSON])} ?~ {"wrong JSON format"}
            correctCoordinates <- checkIfLocationPossible(corpLocationJson.corporate_location.latitude, corpLocationJson.corporate_location.longitude)
            if(addCorpLocation(u.apiId, (now:TimeSpan), corpLocationJson.corporate_location.longitude, corpLocationJson.corporate_location.latitude))
          } yield {
            val successJson = SuccessMessage("corporate location added")
            successJsonResponse(Extraction.decompose(successJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateCounterpartyCorporateLocation",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/corporate_location",
      "Update corporate location of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateCounterpartyCorporateLocation : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //update corporate location of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "corporate_location" :: Nil JsonPut json -> _ => {
        user =>
          for {
            u <- user
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addCorpLocation <- Box(metadata.addCorporateLocation) ?~ {"the view " + viewId + "does not allow updating a corporate location"}
            corpLocationJson <- tryo{(json.extract[CorporateLocationJSON])} ?~ {"wrong JSON format"}
            correctCoordinates <- checkIfLocationPossible(corpLocationJson.corporate_location.latitude, corpLocationJson.corporate_location.longitude)
            if(addCorpLocation(u.apiId, now, corpLocationJson.corporate_location.longitude, corpLocationJson.corporate_location.latitude))
          } yield {
            val successJson = SuccessMessage("corporate location updated")
            successJsonResponse(Extraction.decompose(successJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteCounterpartyCorporateLocation",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/corporate_location",
      "Delete corporate location of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteCounterpartyCorporateLocation : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete corporate location of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "corporate_location" :: Nil JsonDelete _ => {
        user =>
          for {
            u <- user
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            deleted <- Box(metadata.deleteCorporateLocation)
          } yield {
            if(deleted())
              noContentJsonResponse
            else
              errorJsonResponse("Delete not completed")
          }
      }
    }

    //TODO: get physical location of counterparty?

    resourceDocs += ResourceDoc(
      apiVersion,
      "addCounterpartyPhysicalLocation",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/physical_location",
      "Add physical location to other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addCounterpartyPhysicalLocation : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add physical location to other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts" :: other_account_id :: "physical_location" :: Nil JsonPost json -> _ => {
        user =>
          for {
            u <- user
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addPhysicalLocation <- Box(metadata.addPhysicalLocation) ?~ {"the view " + viewId + "does not allow adding a physical location"}
            physicalLocationJson <- tryo{(json.extract[PhysicalLocationJSON])} ?~ {"wrong JSON format"}
            correctCoordinates <- checkIfLocationPossible(physicalLocationJson.physical_location.latitude, physicalLocationJson.physical_location.longitude)
            if(addPhysicalLocation(u.apiId, now, physicalLocationJson.physical_location.longitude, physicalLocationJson.physical_location.latitude))
          } yield {
            val successJson = SuccessMessage("physical location added")
            successJsonResponse(Extraction.decompose(successJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateCounterpartyPhysicalLocation",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/physical_location",
      "Update physical location to other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateCounterpartyPhysicalLocation : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //update physical location to other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "physical_location" :: Nil JsonPut json -> _ => {
        user =>
          for {
            u <- user
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            addPhysicalLocation <- Box(metadata.addPhysicalLocation) ?~ {"the view " + viewId + "does not allow updating a physical location"}
            physicalLocationJson <- tryo{(json.extract[PhysicalLocationJSON])} ?~ {"wrong JSON format"}
            correctCoordinates <- checkIfLocationPossible(physicalLocationJson.physical_location.latitude, physicalLocationJson.physical_location.longitude)
            if(addPhysicalLocation(u.apiId, now, physicalLocationJson.physical_location.longitude, physicalLocationJson.physical_location.latitude))
          } yield {
            val successJson = SuccessMessage("physical location updated")
            successJsonResponse(Extraction.decompose(successJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteCounterpartyPhysicalLocation",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/other_accounts/OTHER_ACCOUNT_ID/physical_location",
      "Delete physical location of other bank account.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteCounterpartyPhysicalLocation : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete physical location of other bank account
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "other_accounts":: other_account_id :: "physical_location" :: Nil JsonDelete _ => {
        user =>
          for {
            u <- user
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            otherBankAccount <- account.moderatedOtherBankAccount(other_account_id, view, user)
            metadata <- Box(otherBankAccount.metadata) ?~ {"the view " + viewId + "does not allow metadata access"}
            deleted <- Box(metadata.deletePhysicalLocation)
          } yield {
            if(deleted())
              noContentJsonResponse
            else
              errorJsonResponse("Delete not completed")
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getTransactionsForBankAccount",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions",
      "Get transactions.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getTransactionsForBankAccount : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get transactions
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: Nil JsonGet json => {
        user =>

          for {
            params <- APIMethods121.getTransactionParams(json)
            bankAccount <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, bankAccount)
            transactions <- bankAccount.getModeratedTransactions(user, view, params : _*)
          } yield {
            val json = JSONFactory.createTransactionsJSON(transactions)
            successJsonResponse(Extraction.decompose(json))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getTransactionByIdForBankAccount",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/transaction",
      "Get transaction by id.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getTransactionByIdForBankAccount : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get transaction by id
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "transaction" :: Nil JsonGet json => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            moderatedTransaction <- account.moderatedTransaction(transactionId, view, user)
          } yield {
            val json = JSONFactory.createTransactionJSON(moderatedTransaction)
            successJsonResponse(Extraction.decompose(json))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getTransactionNarrative",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/narrative",
      "Get narrative.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getTransactionNarrative : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get narrative
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "narrative" :: Nil JsonGet json => {
        user =>
          for {
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            narrative <- Box(metadata.ownerComment) ?~ { "view " + viewId + " does not authorize narrative access" }
          } yield {
            val narrativeJson = JSONFactory.createTransactionNarrativeJSON(narrative)
            successJsonResponse(Extraction.decompose(narrativeJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "addTransactionNarrative",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/narrative",
      "Add narrative.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addTransactionNarrative : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add narrative
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "narrative" :: Nil JsonPost json -> _ => {
        user =>
          for {
            u <- user
            narrativeJson <- tryo{json.extract[TransactionNarrativeJSON]} ?~ {"wrong json format"}
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, Full(u))
            addNarrative <- Box(metadata.addOwnerComment) ?~ {"view " + viewId + " does not allow adding a narrative"}
          } yield {
            addNarrative(narrativeJson.narrative)
            val successJson = SuccessMessage("narrative added")
            successJsonResponse(Extraction.decompose(successJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateTransactionNarrative",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/narrative",
      "Update narrative.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateTransactionNarrative : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //update narrative
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "narrative" :: Nil JsonPut json -> _ => {
        user =>
          for {
            u <- user
            narrativeJson <- tryo{json.extract[TransactionNarrativeJSON]} ?~ {"wrong json format"}
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, Full(u))
            addNarrative <- Box(metadata.addOwnerComment) ?~ {"view " + viewId + " does not allow updating a narrative"}
          } yield {
            addNarrative(narrativeJson.narrative)
            val successJson = SuccessMessage("narrative updated")
            successJsonResponse(Extraction.decompose(successJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteTransactionNarrative",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/narrative",
      "Delete narrative.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteTransactionNarrative : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete narrative
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "narrative" :: Nil JsonDelete _ => {
        user =>
          for {
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            addNarrative <- Box(metadata.addOwnerComment) ?~ {"view " + viewId + " does not allow deleting the narrative"}
          } yield {
            addNarrative("")
            noContentJsonResponse
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getCommentsForViewOnTransaction",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/comments",
      "Get comments.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getCommentsForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get comments
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "comments" :: Nil JsonGet json => {
        user =>
          for {
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            comments <- Box(metadata.comments) ?~ { "view " + viewId + " does not authorize comments access" }
          } yield {
            val json = JSONFactory.createTransactionCommentsJSON(comments)
            successJsonResponse(Extraction.decompose(json))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "addCommentForViewOnTransaction",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/comments",
      "Add comment.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addCommentForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add comment
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "comments" :: Nil JsonPost json -> _ => {
        user =>
          for {
            u <- user
            commentJson <- tryo{json.extract[PostTransactionCommentJSON]} ?~ {"wrong json format"}
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, Full(u))
            addCommentFunc <- Box(metadata.addComment) ?~ {"view " + viewId + " does not authorize adding comments"}
            postedComment <- addCommentFunc(u.apiId, viewId, commentJson.value, now)
          } yield {
            successJsonResponse(Extraction.decompose(JSONFactory.createTransactionCommentJSON(postedComment)),201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteCommentForViewOnTransaction",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/comments/COMMENT_ID",
      "Delete comment.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteCommentForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete comment
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "comments":: commentId :: Nil JsonDelete _ => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            delete <- metadata.deleteComment(commentId, user, account)
          } yield {
            noContentJsonResponse
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getTagsForViewOnTransaction",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/tags",
      "Get tags.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getTagsForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get tags
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "tags" :: Nil JsonGet json => {
        user =>
          for {
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            tags <- Box(metadata.tags) ?~ { "view " + viewId + " does not authorize tag access" }
          } yield {
            val json = JSONFactory.createTransactionTagsJSON(tags)
            successJsonResponse(Extraction.decompose(json))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "addTagForViewOnTransaction",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/tags",
      "Add a tag.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addTagForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add a tag
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "tags" :: Nil JsonPost json -> _ => {

        user =>
          for {
            u <- user
            tagJson <- tryo{json.extract[PostTransactionTagJSON]}
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, Full(u))
            addTagFunc <- Box(metadata.addTag) ?~ {"view " + viewId + " does not authorize adding tags"}
            postedTag <- addTagFunc(u.apiId, viewId, tagJson.value, now)
          } yield {
            successJsonResponse(Extraction.decompose(JSONFactory.createTransactionTagJSON(postedTag)), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteTagForViewOnTransaction",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/tags/TAG_ID",
      "Delete a tag.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteTagForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete a tag
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "tags" :: tagId :: Nil JsonDelete _ => {

        user =>
          for {
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            bankAccount <- BankAccount(bankId, accountId)
            deleted <- metadata.deleteTag(tagId, user, bankAccount)
          } yield {
            noContentJsonResponse
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getImagesForViewOnTransaction",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/images",
      "Get images.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getImagesForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get images
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "images" :: Nil JsonGet json => {
        user =>
          for {
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            images <- Box(metadata.images) ?~ { "view " + viewId + " does not authorize images access" }
          } yield {
            val json = JSONFactory.createTransactionImagesJSON(images)
            successJsonResponse(Extraction.decompose(json))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "addImageForViewOnTransaction",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/images",
      "Add an image.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addImageForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add an image
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "images" :: Nil JsonPost json -> _ => {
        user =>
          for {
            u <- user
            imageJson <- tryo{json.extract[PostTransactionImageJSON]}
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, Full(u))
            addImageFunc <- Box(metadata.addImage) ?~ {"view " + viewId + " does not authorize adding images"}
            url <- tryo{new URL(imageJson.URL)} ?~! "Could not parse url string as a valid URL"
            postedImage <- addImageFunc(u.apiId, viewId, imageJson.label, now, url)
          } yield {
            successJsonResponse(Extraction.decompose(JSONFactory.createTransactionImageJSON(postedImage)),201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteImageForViewOnTransaction",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/images/IMAGE_ID",
      "delete an image",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteImageForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete an image
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "images" :: imageId :: Nil JsonDelete _ => {
        user =>
          for {
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            bankAccount <- BankAccount(bankId, accountId)
            deleted <- Box(metadata.deleteImage(imageId, user, bankAccount))
          } yield {
            noContentJsonResponse
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getWhereTagForViewOnTransaction",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/where",
      "Get where tag.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getWhereTagForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get where tag
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "where" :: Nil JsonGet json => {
        user =>
          for {
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            where <- Box(metadata.whereTag) ?~ { "view " + viewId + " does not authorize where tag access" }
          } yield {
            val json = JSONFactory.createLocationJSON(where)
            val whereJson = TransactionWhereJSON(json)
            successJsonResponse(Extraction.decompose(whereJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "addWhereTagForViewOnTransaction",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/where",
      "Add where tag.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val addWhereTagForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //add where tag
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "where" :: Nil JsonPost json -> _ => {
        user =>
          for {
            u <- user
            view <- View.fromUrl(viewId, accountId, bankId)
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            addWhereTag <- Box(metadata.addWhereTag) ?~ {"the view " + viewId + "does not allow adding a where tag"}
            whereJson <- tryo{(json.extract[PostTransactionWhereJSON])} ?~ {"wrong JSON format"}
            correctCoordinates <- checkIfLocationPossible(whereJson.where.latitude, whereJson.where.longitude)
            if(addWhereTag(u.apiId, viewId, now, whereJson.where.longitude, whereJson.where.latitude))
          } yield {
            val successJson = SuccessMessage("where tag added")
            successJsonResponse(Extraction.decompose(successJson), 201)
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "updateWhereTagForViewOnTransaction",
      "PUT",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/where",
      "Update where tag.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val updateWhereTagForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //update where tag
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "where" :: Nil JsonPut json -> _ => {
        user =>
          for {
            u <- user
            view <- View.fromUrl(viewId, accountId, bankId)
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            addWhereTag <- Box(metadata.addWhereTag) ?~ {"the view " + viewId + "does not allow updating a where tag"}
            whereJson <- tryo{(json.extract[PostTransactionWhereJSON])} ?~ {"wrong JSON format"}
            correctCoordinates <- checkIfLocationPossible(whereJson.where.latitude, whereJson.where.longitude)
            if(addWhereTag(u.apiId, viewId, now, whereJson.where.longitude, whereJson.where.latitude))
          } yield {
            val successJson = SuccessMessage("where tag updated")
            successJsonResponse(Extraction.decompose(successJson))
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "deleteWhereTagForViewOnTransaction",
      "DELETE",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/metadata/where",
      "Delete where tag.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val deleteWhereTagForViewOnTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //delete where tag
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: TransactionId(transactionId) :: "metadata" :: "where" :: Nil JsonDelete _ => {
        user =>
          for {
            bankAccount <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, bankAccount)
            metadata <- moderatedTransactionMetadata(bankId, accountId, viewId, transactionId, user)
            deleted <- metadata.deleteWhereTag(viewId, user, bankAccount)
          } yield {
            if(deleted)
              noContentJsonResponse
            else
              errorJsonResponse("Delete not completed")
          }
      }
    }

    resourceDocs += ResourceDoc(
      apiVersion,
      "getCounterpartyForTransaction",
      "GET",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions/TRANSACTION_ID/other_account",
      "Get other account of a transaction.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val getCounterpartyForTransaction : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      //get other account of a transaction
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions":: TransactionId(transactionId) :: "other_account" :: Nil JsonGet json => {
        user =>
          for {
            account <- BankAccount(bankId, accountId)
            view <- View.fromUrl(viewId, account)
            transaction <- account.moderatedTransaction(transactionId, view, user)
            moderatedOtherBankAccount <- transaction.otherBankAccount
          } yield {
            val otherBankAccountJson = JSONFactory.createOtherBankAccount(moderatedOtherBankAccount)
            successJsonResponse(Extraction.decompose(otherBankAccountJson))
          }

      }
    }

    case class TransactionIdJson(transaction_id : String)

    resourceDocs += ResourceDoc(
      apiVersion,
      "makePayment",
      "POST",
      "/banks/BANK_ID/accounts/ACCOUNT_ID/VIEW_ID/transactions",
      "Make Payment.",
      emptyObjectJson,
      emptyObjectJson)

    lazy val makePayment : PartialFunction[Req, Box[User] => Box[JsonResponse]] = {
      case "banks" :: BankId(bankId) :: "accounts" :: AccountId(accountId) :: ViewId(viewId) :: "transactions" :: Nil JsonPost json -> _ => {
        user =>

          if (Props.getBool("payments_enabled", false)) {
            for {
              u <- user ?~ "User not found"
              makeTransJson <- tryo{json.extract[MakePaymentJson]} ?~ {"wrong json format"}
              rawAmt <- tryo {BigDecimal(makeTransJson.amount)} ?~! s"amount ${makeTransJson.amount} not convertible to number"
              toAccountUID = BankAccountUID(BankId(makeTransJson.bank_id), AccountId(makeTransJson.account_id))
              createdPaymentId <- Connector.connector.vend.makePayment(u, BankAccountUID(bankId, accountId), toAccountUID, rawAmt)
            } yield {
              val successJson = Extraction.decompose(TransactionIdJson(createdPaymentId.value))
              successJsonResponse(successJson)
            }
          } else{
            Failure("Sorry, payments are not enabled in this API instance.")
          }

      }
    }
  }
}

object APIMethods121 {
  import java.util.Date

  object DateParser {

    /**
    * first tries to parse dates using this pattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" (2012-07-01T00:00:00.000Z) ==> time zone is UTC
    * in case of failure (for backward compatibility reason), try "yyyy-MM-dd'T'HH:mm:ss.SSSZ" (2012-07-01T00:00:00.000+0000) ==> time zone has to be specified
    */
    def parse(date: String): Box[Date] = {
      import java.text.SimpleDateFormat

      val defaultFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      val fallBackFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

      val parsedDate = tryo{
        defaultFormat.parse(date)
      }

      lazy val fallBackParsedDate = tryo{
        fallBackFormat.parse(date)
      }

      if(parsedDate.isDefined){
        Full(parsedDate.get)
      }
      else if(fallBackParsedDate.isDefined){
        Full(fallBackParsedDate.get)
      }
      else{
        Failure(s"Failed to parse date string. Please use this format ${defaultFormat.toPattern} or that one ${fallBackFormat.toPattern}")
      }
    }
  }

  private def getSortDirection(req: Req): Box[OBPOrder] = {
    req.header("obp_sort_direction") match {
      case Full(v) => {
        if(v.toLowerCase == "desc" || v.toLowerCase == "asc"){
          Full(OBPOrder(Some(v.toLowerCase)))
        }
        else{
          Failure("obp_sort_direction parameter can only take two values: DESC or ASC")
        }
      }
      case _ => Full(OBPOrder(None))
    }
  }

  private def getFromDate(req: Req): Box[OBPFromDate] = {
    val date: Box[Date] = req.header("obp_from_date") match {
      case Full(d) => {
        DateParser.parse(d)
      }
      case _ => {
        Full(new Date(0))
      }
    }

    date.map(OBPFromDate(_))
  }

  private def getToDate(req: Req): Box[OBPToDate] = {
    val date: Box[Date] = req.header("obp_to_date") match {
      case Full(d) => {
        DateParser.parse(d)
      }
      case _ => Full(new Date())
    }

    date.map(OBPToDate(_))
  }

  private def getOffset(req: Req): Box[OBPOffset] = {
    val msg = "wrong value for obp_offset parameter. Please send a positive integer (=>0)"
    getPaginationParam(req, "obp_offset", 0, 0, msg)
    .map(OBPOffset(_))
  }

  private def getLimit(req: Req): Box[OBPLimit] = {
    val msg = "wrong value for obp_limit parameter. Please send a positive integer (=>1)"
    getPaginationParam(req, "obp_limit", 50, 1, msg)
    .map(OBPLimit(_))
  }

  private def getPaginationParam(req: Req, paramName: String, defaultValue: Int, minimumValue: Int, errorMsg: String): Box[Int]= {
    req.header(paramName) match {
      case Full(v) => {
        tryo{
          v.toInt
        } match {
          case Full(value) => {
            if(value >= minimumValue){
              Full(value)
            }
            else{
              Failure(errorMsg)
            }
          }
          case _ => Failure(errorMsg)
        }
      }
      case _ => Full(defaultValue)
    }
  }

  def getTransactionParams(req: Req): Box[List[OBPQueryParam]] = {
    for{
      sortDirection <- getSortDirection(req)
      fromDate <- getFromDate(req)
      toDate <- getToDate(req)
      limit <- getLimit(req)
      offset <- getOffset(req)
    }yield{

      /**
       * sortBy is currently disabled as it would open up a security hole:
       *
       * sortBy as currently implemented will take in a parameter that searches on the mongo field names. The issue here
       * is that it will sort on the true value, and not the moderated output. So if a view is supposed to return an alias name
       * rather than the true value, but someone uses sortBy on the other bank account name/holder, not only will the returned data
       * have the wrong order, but information about the true account holder name will be exposed due to its position in the sorted order
       *
       * This applies to all fields that can have their data concealed... which in theory will eventually be most/all
       *
       */
      //val sortBy = json.header("obp_sort_by")
      val sortBy = None
      val ordering = OBPOrdering(sortBy, sortDirection)
      limit :: offset :: ordering :: fromDate :: toDate :: Nil
    }
  }
}
