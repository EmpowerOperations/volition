using System;
using System.Collections.Generic;

namespace EmpowerOps.Volition.RefClient
{
    public class EvaluationResult
    {
        public enum ResultStatus
        {
            Succeed, Failed, Canceled
        }

        public ResultStatus Status { get; set; }
        public Exception Exception { get; set; }
       
        public IDictionary<string, double> Output { get; set; }
        public IDictionary<string, double> Input { get; set; }

    }

}
