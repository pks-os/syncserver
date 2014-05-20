package es.caib.seycon.ng.sync.web.esso;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mortbay.log.Log;
import org.mortbay.log.Logger;

import es.caib.seycon.ng.comu.Account;
import es.caib.seycon.ng.comu.Sessio;
import es.caib.seycon.ng.comu.Usuari;
import es.caib.seycon.ng.comu.sso.Secret;
import es.caib.seycon.ng.exception.InternalErrorException;
import es.caib.seycon.ng.exception.UnknownUserException;
import es.caib.seycon.ng.servei.AccountService;
import es.caib.seycon.ng.servei.SessioService;
import es.caib.seycon.ng.servei.UsuariService;
import es.caib.seycon.ng.sync.ServerServiceLocator;
import es.caib.seycon.ng.sync.servei.SecretStoreService;

public class GetSecretsServlet extends HttpServlet {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    Logger log = Log.getLogger("GetSecretsServlet");

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
            IOException {

        String user = req.getParameter("user");
        String key = req.getParameter("key");
        String key2 = req.getParameter("key2");
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(resp.getOutputStream(),
                "UTF-8"));
        SessioService ss = ServerServiceLocator.instance().getSessioService();
        UsuariService usuariService = ServerServiceLocator.instance().getUsuariService();
        try {
            Usuari usuari = usuariService.findUsuariByCodiUsuari(user);
            if (usuari == null)
                throw new UnknownUserException(user);

            for (Sessio sessio : ss.getActiveSessions(usuari.getId())) {
                if (sessio.getClau().equals(key) && sessio.getClauTemporal().equals(key2)) {
                    writer.write(doSecretsAction(usuari, key));
                    writer.close();
                    return;
                }
            }
            writer.write("ERROR|Invalid key");
            log.warn("Invalid key {} for user {}", key, user);
        } catch (Exception e) {
            log.warn("Error getting keys", e);
            writer.write(e.getClass().getName() + "|" + e.getMessage() + "\n");
        }
        writer.close();

    }

    private String doSecretsAction(Usuari user, String sessionKey) throws InternalErrorException {
        StringBuffer result = new StringBuffer("OK");
        SecretStoreService sss = ServerServiceLocator.instance().getSecretStoreService();
        for (Secret secret : sss.getAllSecrets(user)) {
            result.append('|');
            result.append(secret.getName());
            result.append('|');
            result.append(secret.getValue().getPassword());

        }
        result.append ("|sessionKey|").append(sessionKey);
        
        return result.toString();
    }

}
