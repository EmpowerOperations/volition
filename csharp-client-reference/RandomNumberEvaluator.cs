using System;
using System.Collections;
using System.Threading;
using System.Threading.Tasks;
using Google.Protobuf.Collections;

namespace EmpowerOps.Volition.RefClient
{
    public class RandomNumberEvaluator : IEvaluator
    {
        private CancellationTokenSource _evaluationCancellationTokenSource;
        private bool _failToggle = false;

        public void Cancel()
        {
            _evaluationCancellationTokenSource.Cancel();
        }

        public EvaluationResult Evaluate(MapField<string, double> inputs, IList outputs)
        {
            _evaluationCancellationTokenSource = new CancellationTokenSource();
            return Task.Run(()=>SimulationEvaluation(inputs, outputs), _evaluationCancellationTokenSource.Token).Result;
        }

        private EvaluationResult SimulationEvaluation(MapField<string, double> inputs, IList outputs)
        {
            try
            {
                Thread.Sleep(2000);
                _evaluationCancellationTokenSource.Token.ThrowIfCancellationRequested();
                if (_failToggle)
                {
                    _failToggle = false;
                    throw new EvaluationException($"Error evaluating {inputs}");
                }

                Thread.Sleep(2000);
                _evaluationCancellationTokenSource.Token.ThrowIfCancellationRequested();

                var result = new MapField<string, double>();
                var random = new Random();

                foreach (Output output in outputs)
                {
                    var evaluationResult = random.NextDouble();
                    result.Add(output.Name, evaluationResult);
                }

                return new EvaluationResult { Input = inputs, Output = result, Status = EvaluationResult.ResultStatus.Succeed };
            }
            catch (OperationCanceledException)
            {
                return new EvaluationResult { Input = inputs, Output = new MapField<string, double>(), Status = EvaluationResult.ResultStatus.Canceled };
            }
            catch (EvaluationException e)
            {
                var result = new MapField<string, double>();
                foreach (Output output in outputs)
                {
                    var evaluationResult = Double.PositiveInfinity;
                    result.Add(output.Name, evaluationResult);
                }

                return new EvaluationResult
                {
                    Input = inputs,
                    Output = result,
                    Status = EvaluationResult.ResultStatus.Failed,
                    Exception = e
                };
            }
            catch (Exception e)
            {
                return new EvaluationResult
                {
                    Input = inputs,
                    Output = new MapField<string, double>(),
                    Status = EvaluationResult.ResultStatus.Failed,
                    Exception = e
                };
            }
        }

        public void SetFailNext()
        {
            _failToggle = true;
        }
    }

}
